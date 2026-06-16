using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CallingBotSample.Options;
using CallingBotSample.Security;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace CallingBotSample.Services.SyncLingo
{
    public class SyncLingoBotQueryService : ISyncLingoBotQueryService
    {
        private static readonly JsonSerializerOptions JsonOptions = new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true,
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase
        };
        private const string BotSecretHeader = "X-SyncLingo-Bot-Secret";
        /// <summary>上行签名 keyId,Java 侧用它选择对应的验签密钥。</summary>
        private const string UpstreamKeyId = "csharp-bot";

        private readonly HttpClient httpClient;
        private readonly BotOptions botOptions;
        private readonly ILogger<SyncLingoBotQueryService> logger;

        public SyncLingoBotQueryService(
            HttpClient httpClient,
            IOptions<BotOptions> botOptions,
            ILogger<SyncLingoBotQueryService> logger)
        {
            this.httpClient = httpClient;
            this.botOptions = botOptions.Value;
            this.logger = logger;
        }

        public async Task StreamQueryAsync(
            TeamsBotUserContext userContext,
            string message,
            IReadOnlyList<SyncLingoBotChatTurn>? history,
            Func<string, Task> chunkCallback,
            CancellationToken cancellationToken)
        {
            logger.LogInformation("[SyncLingoBotQueryService] StreamQuery start, aadId={AadId}, messageLen={MessageLen}",
                userContext.AadId, message?.Length ?? 0);

            if (botOptions.BackendBaseUrl == null)
            {
                await chunkCallback("Bot 还没有配置 syncLingo 后端地址，暂时不能查询会议记录。");
                return;
            }

            var endpoint = new Uri(botOptions.BackendBaseUrl, "api/teams-bot/query/stream");
            var request = new SyncLingoBotQueryRequest
            {
                AadId = userContext.AadId,
                Mail = userContext.Mail,
                UserPrincipalName = userContext.UserPrincipalName,
                DisplayName = userContext.DisplayName,
                Message = message ?? string.Empty,
                History = history == null ? null : new List<SyncLingoBotChatTurn>(history)
            };

            try
            {
                using var httpRequest = BuildSignedRequest(endpoint, request);

                using var response = await httpClient.SendAsync(
                    httpRequest,
                    HttpCompletionOption.ResponseHeadersRead,
                    cancellationToken);

                if (!response.IsSuccessStatusCode)
                {
                    var body = await response.Content.ReadAsStringAsync(cancellationToken);
                    logger.LogWarning("[SyncLingoBotQueryService] StreamQuery backend returned HTTP {StatusCode}, body={Body}",
                        (int)response.StatusCode, body);
                    await chunkCallback("会议记录查询服务暂时不可用，请稍后再试。");
                    return;
                }

                using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
                using var reader = new StreamReader(stream);

                while (!reader.EndOfStream && !cancellationToken.IsCancellationRequested)
                {
                    var line = await reader.ReadLineAsync();
                    if (line == null) break;
                    // SSE spec: field name is "data", value follows after optional single space
                    // Spring SseEmitter sends "data:value" (no space); tolerate both formats
                    if (!line.StartsWith("data:")) continue;
                    var data = line.Substring(5).TrimStart(' ');
                    if (data == "[DONE]") break;
                    if (data == "[ERROR]")
                    {
                        logger.LogWarning("[SyncLingoBotQueryService] StreamQuery received [ERROR]");
                        break;
                    }
                    if (data.StartsWith("[STATUS]", StringComparison.OrdinalIgnoreCase))
                        continue;
                    if (!string.IsNullOrEmpty(data))
                        await chunkCallback(data);
                }
                logger.LogInformation("[SyncLingoBotQueryService] StreamQuery done, aadId={AadId}", userContext.AadId);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "[SyncLingoBotQueryService] StreamQuery failed, aadId={AadId}", userContext.AadId);
                await chunkCallback("查询会议记录时遇到错误，请稍后再试。");
            }
        }

        public async Task<SyncLingoBotQueryResponse> QueryAsync(
            TeamsBotUserContext userContext,
            string message,
            IReadOnlyList<SyncLingoBotChatTurn>? history,
            CancellationToken cancellationToken)
        {
            logger.LogInformation("[SyncLingoBotQueryService] Query start, aadId={AadId}, upn={Upn}, messageLen={MessageLen}",
                userContext.AadId, userContext.UserPrincipalName, message?.Length ?? 0);

            if (botOptions.BackendBaseUrl == null)
                return ErrorResponse("Bot 还没有配置 syncLingo 后端地址，暂时不能查询会议记录。");

            var endpoint = new Uri(botOptions.BackendBaseUrl, "api/teams-bot/query");
            var request = new SyncLingoBotQueryRequest
            {
                AadId = userContext.AadId,
                Mail = userContext.Mail,
                UserPrincipalName = userContext.UserPrincipalName,
                DisplayName = userContext.DisplayName,
                Message = message ?? string.Empty,
                History = history == null ? null : new List<SyncLingoBotChatTurn>(history)
            };

            try
            {
                using var httpRequest = BuildSignedRequest(endpoint, request);

                using var response = await httpClient.SendAsync(httpRequest, cancellationToken);
                var body = await response.Content.ReadAsStringAsync(cancellationToken);
                if (!response.IsSuccessStatusCode)
                {
                    logger.LogWarning("[SyncLingoBotQueryService] Query backend returned HTTP {StatusCode}, body={Body}",
                        (int)response.StatusCode, body);
                    return ErrorResponse("会议记录查询服务暂时不可用，请稍后再试。");
                }

                var result = JsonSerializer.Deserialize<SyncLingoResult<SyncLingoBotQueryResponse>>(body, JsonOptions);
                if (result == null)
                {
                    logger.LogWarning("[SyncLingoBotQueryService] Query backend response empty.");
                    return ErrorResponse("会议记录查询服务返回了空响应。");
                }

                if (result.Code != 200)
                {
                    logger.LogWarning("[SyncLingoBotQueryService] Query backend failed, code={Code}, message={Message}",
                        result.Code, result.Message);
                    return ErrorResponse(string.IsNullOrWhiteSpace(result.Message) ? "会议记录查询失败。" : result.Message);
                }

                var data = result.Data ?? ErrorResponse("没有可返回的会议记录内容。");
                var reply = data.ReplyText;
                logger.LogInformation("[SyncLingoBotQueryService] Query end, aadId={AadId}, replyLen={ReplyLen}",
                    userContext.AadId, reply?.Length ?? 0);
                if (string.IsNullOrWhiteSpace(reply))
                    data.ReplyText = "没有可返回的会议记录内容。";
                return data;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "[SyncLingoBotQueryService] Query failed, aadId={AadId}", userContext.AadId);
                return ErrorResponse("查询会议记录时遇到错误，请稍后再试。");
            }
        }

        /// <summary>
        /// 构造发往 Java 后端的请求:序列化为确定字节(便于签名),附带静态 api-secret,
        /// 并在配置了 upstream-key 时附加 HMAC 服务签名(对相同字节签名,与 Java 验签一致)。
        /// </summary>
        private HttpRequestMessage BuildSignedRequest(Uri endpoint, SyncLingoBotQueryRequest request)
        {
            var json = JsonSerializer.Serialize(request, JsonOptions);
            var bodyBytes = Encoding.UTF8.GetBytes(json);

            var httpRequest = new HttpRequestMessage(HttpMethod.Post, endpoint)
            {
                Content = new ByteArrayContent(bodyBytes)
            };
            httpRequest.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json") { CharSet = "utf-8" };

            if (!string.IsNullOrWhiteSpace(botOptions.BackendApiSecret))
                httpRequest.Headers.Add(BotSecretHeader, botOptions.BackendApiSecret);

            var upstreamKey = botOptions.ServiceSignatureUpstreamKey;
            if (!string.IsNullOrWhiteSpace(upstreamKey))
            {
                var rawQuery = endpoint.Query.TrimStart('?');
                var headers = ServiceSignature.Sign(
                    upstreamKey, UpstreamKeyId, "POST", endpoint.AbsolutePath, rawQuery, bodyBytes);
                foreach (var kv in headers)
                    httpRequest.Headers.Add(kv.Key, kv.Value);
            }

            return httpRequest;
        }

        private static SyncLingoBotQueryResponse ErrorResponse(string text)
        {
            return new SyncLingoBotQueryResponse
            {
                ReplyText = text,
                ResponseType = "error",
                Sources = new List<SyncLingoBotQuerySource>()
            };
        }
    }

    public class SyncLingoBotQueryRequest
    {
        public string? AadId { get; set; }

        public string? Mail { get; set; }

        public string? UserPrincipalName { get; set; }

        public string? DisplayName { get; set; }

        public string Message { get; set; } = string.Empty;

        public List<SyncLingoBotChatTurn>? History { get; set; }
    }

    public class SyncLingoBotQueryResponse
    {
        public string? ReplyText { get; set; }

        public string? ResponseType { get; set; }

        public List<SyncLingoBotQuerySource>? Sources { get; set; }

        public bool UserMatched { get; set; }

        public long? UserId { get; set; }

        public string? Command { get; set; }
    }

    public class SyncLingoBotQuerySource
    {
        public string? SourceType { get; set; }

        public string? Title { get; set; }

        public string? MeetingTitle { get; set; }

        public string? SessionId { get; set; }

        public long? MeetingId { get; set; }

        public long? FileId { get; set; }

        public string? SourceName { get; set; }

        public string? SourceDate { get; set; }

        public string? Snippet { get; set; }

        public float? Score { get; set; }
    }

    public class SyncLingoResult<T>
    {
        public int Code { get; set; }

        public string? Message { get; set; }

        public T? Data { get; set; }
    }
}
