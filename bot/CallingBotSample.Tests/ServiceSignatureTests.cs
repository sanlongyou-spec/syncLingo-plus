using System.Collections.Generic;
using System.Text;
using System.Threading.Tasks;
using CallingBotSample.Options;
using CallingBotSample.Security;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace CallingBotSample.Tests
{
    /// <summary>
    /// P4 服务签名:C# 必须与 Java 后端逐字节一致(跨运行时互验),并满足签发-验签往返、防篡改、重放。
    /// </summary>
    public class ServiceSignatureTests
    {
        private const string Secret = "downstream_key_at_least_32_chars_long_xx";

        [Fact]
        public void CrossRuntimeVector_MatchesJavaAndDotNetReference()
        {
            // 与 Java ServiceSignatureTest.crossRuntimeVector_matchesDotNetReference 同一向量与期望值。
            var canonical = ServiceSignature.Canonical(
                "java-backend", "1700000000", "abc123",
                "POST", "/api/meetings/summary", "", Encoding.UTF8.GetBytes("{\"x\":1}"));
            var signature = ServiceSignature.HmacBase64("test_secret_key_at_least_32_chars_xxxxxx", canonical);
            Assert.Equal("258QrV2Z2eWLck5cYzmmvFoEeAytfcd8htB2uwqpUi4=", signature);
        }

        [Fact]
        public void SignThenVerify_RoundTrip_Succeeds()
        {
            var body = Encoding.UTF8.GetBytes("{\"meetingId\":7}");
            var headers = ServiceSignature.Sign(Secret, "java-backend", "POST", "/api/meetings/summary", "", body);
            var ok = ServiceSignature.TryVerify(
                Secret, name => headers.TryGetValue(name, out var v) ? v : null,
                "POST", "/api/meetings/summary", "", body, out var reason);
            Assert.True(ok, reason);
        }

        [Fact]
        public void TamperedBody_IsRejected()
        {
            var headers = ServiceSignature.Sign(Secret, "java-backend", "POST", "/p", "", Encoding.UTF8.GetBytes("a"));
            var ok = ServiceSignature.TryVerify(
                Secret, name => headers.TryGetValue(name, out var v) ? v : null,
                "POST", "/p", "", Encoding.UTF8.GetBytes("b"), out _);
            Assert.False(ok);
        }

        [Fact]
        public void ReplayedNonce_IsRejected()
        {
            var body = Encoding.UTF8.GetBytes("x");
            var headers = ServiceSignature.Sign(Secret, "java-backend", "POST", "/a", "", body);
            string? Header(string name) => headers.TryGetValue(name, out var v) ? v : null;
            Assert.True(ServiceSignature.TryVerify(Secret, Header, "POST", "/a", "", body, out _));
            Assert.False(ServiceSignature.TryVerify(Secret, Header, "POST", "/a", "", body, out _));
        }

        [Fact]
        public void MissingHeaders_IsRejected()
        {
            var ok = ServiceSignature.TryVerify(
                Secret, _ => null, "POST", "/a", "", Encoding.UTF8.GetBytes("x"), out _);
            Assert.False(ok);
        }

        [Fact]
        public async Task Middleware_MissingSignature_IsRejectedByDefault()
        {
            var called = false;
            var middleware = NewMiddleware(_ =>
            {
                called = true;
                return Task.CompletedTask;
            }, new BotOptions { ServiceSignatureDownstreamKey = Secret });
            var context = new DefaultHttpContext();
            context.Request.Method = "POST";
            context.Request.Path = "/api/meetings/summary";
            context.Request.Body = new System.IO.MemoryStream(Encoding.UTF8.GetBytes("{}"));

            await middleware.InvokeAsync(context);

            Assert.False(called);
            Assert.Equal(StatusCodes.Status401Unauthorized, context.Response.StatusCode);
        }

        [Fact]
        public async Task Middleware_MissingSignature_IsAllowedInExplicitMigrationMode()
        {
            var called = false;
            var middleware = NewMiddleware(_ =>
            {
                called = true;
                return Task.CompletedTask;
            }, new BotOptions
            {
                ServiceSignatureDownstreamKey = Secret,
                RequireServiceSignatureDownstream = false
            });
            var context = new DefaultHttpContext();
            context.Request.Method = "POST";
            context.Request.Path = "/api/meetings/summary";
            context.Request.Body = new System.IO.MemoryStream(Encoding.UTF8.GetBytes("{}"));

            await middleware.InvokeAsync(context);

            Assert.True(called);
        }

        private static TeamsBotInboundSignatureMiddleware NewMiddleware(
            RequestDelegate next,
            BotOptions options)
        {
            return new TeamsBotInboundSignatureMiddleware(
                next,
                Microsoft.Extensions.Options.Options.Create(options),
                NullLogger<TeamsBotInboundSignatureMiddleware>.Instance);
        }
    }
}
