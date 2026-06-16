using System.IO;
using System.Text;
using System.Threading.Tasks;
using CallingBotSample.Options;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace CallingBotSample.Security
{
    /// <summary>
    /// P4 入站(Java→C#)服务签名校验,仅作用于 <c>/api/meetings</c>。校验 Java 后端用 downstream-key 的签名。
    ///
    /// <para>执行策略:</para>
    /// <list type="bullet">
    ///   <item>未配置 downstream-key:不校验,放行。</item>
    ///   <item>已配置且带签名头:严格验签,失败 401。</item>
    ///   <item>已配置但缺签名头:默认拒绝;仅 RequireServiceSignatureDownstream=false 时迁移放行。</item>
    /// </list>
    /// </summary>
    public class TeamsBotInboundSignatureMiddleware
    {
        private const string MeetingsPrefix = "/api/meetings";

        private readonly RequestDelegate next;
        private readonly BotOptions botOptions;
        private readonly ILogger<TeamsBotInboundSignatureMiddleware> logger;

        public TeamsBotInboundSignatureMiddleware(
            RequestDelegate next,
            IOptions<BotOptions> botOptions,
            ILogger<TeamsBotInboundSignatureMiddleware> logger)
        {
            this.next = next;
            this.botOptions = botOptions.Value;
            this.logger = logger;
        }

        public async Task InvokeAsync(HttpContext context)
        {
            var path = context.Request.Path.Value ?? string.Empty;
            if (!path.StartsWith(MeetingsPrefix))
            {
                await next(context);
                return;
            }

            var downstreamKey = botOptions.ServiceSignatureDownstreamKey;
            if (string.IsNullOrWhiteSpace(downstreamKey))
            {
                await next(context);
                return;
            }

            // 读取并缓存请求体,供验签与后续模型绑定共用。
            context.Request.EnableBuffering();
            var body = await ReadBodyAsync(context.Request);
            context.Request.Body.Position = 0;

            string? Header(string name) => context.Request.Headers.TryGetValue(name, out var v) ? v.ToString() : null;

            if (ServiceSignature.HasSignatureHeaders(Header))
            {
                var rawQuery = context.Request.QueryString.HasValue
                    ? context.Request.QueryString.Value!.TrimStart('?')
                    : string.Empty;
                var ok = ServiceSignature.TryVerify(
                    downstreamKey, Header,
                    context.Request.Method, path, rawQuery, body,
                    out var reason);
                if (!ok)
                {
                    logger.LogWarning("[TeamsBotInboundSignature] rejected, path={Path}, reason={Reason}", path, reason);
                    context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                    context.Response.ContentType = "application/json;charset=UTF-8";
                    await context.Response.WriteAsync("{\"error\":\"unauthorized\"}");
                    return;
                }
            }
            else
            {
                if (botOptions.RequireServiceSignatureDownstream)
                {
                    logger.LogWarning(
                        "[TeamsBotInboundSignature] missing signature rejected, path={Path}", path);
                    context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                    context.Response.ContentType = "application/json;charset=UTF-8";
                    await context.Response.WriteAsync("{\"error\":\"unauthorized\"}");
                    return;
                }
                logger.LogWarning(
                    "[TeamsBotInboundSignature] missing signature (explicit migration mode), path={Path}", path);
            }

            await next(context);
        }

        private static async Task<byte[]> ReadBodyAsync(HttpRequest request)
        {
            using var ms = new MemoryStream();
            await request.Body.CopyToAsync(ms);
            return ms.ToArray();
        }
    }
}
