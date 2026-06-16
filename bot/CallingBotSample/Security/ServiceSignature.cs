using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;

[assembly: InternalsVisibleTo("CallingBotSample.Tests")]

namespace CallingBotSample.Security
{
    /// <summary>
    /// 与 Java 后端 <c>com.si.backend.security.ServiceSignature</c> 逐字节一致的服务间 HMAC-SHA256 签名。
    ///
    /// canonical = keyId \n timestamp \n nonce \n METHOD \n path \n rawQuery \n hexSha256(body)
    /// signature = Base64( HmacSHA256(secret, canonical) )
    /// </summary>
    public static class ServiceSignature
    {
        public const string HeaderKeyId = "X-Svc-Key-Id";
        public const string HeaderTimestamp = "X-Svc-Timestamp";
        public const string HeaderNonce = "X-Svc-Nonce";
        public const string HeaderSignature = "X-Svc-Signature";

        private const long MaxSkewSeconds = 300;
        private const long NonceTtlSeconds = 600;
        private const int MaxNonceEntries = 50_000;

        // nonce -> 过期 epoch 秒,命中即视为重放。
        private static readonly ConcurrentDictionary<string, long> SeenNonces = new();

        /// <summary>生成签名所需的全部请求头(出站方调用)。</summary>
        public static IReadOnlyDictionary<string, string> Sign(
            string secret, string keyId, string method, string path, string rawQuery, byte[] body)
        {
            var timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString();
            var nonce = Guid.NewGuid().ToString("N");
            var canonical = Canonical(keyId, timestamp, nonce, method, path, rawQuery, body);
            var signature = HmacBase64(secret, canonical);
            return new Dictionary<string, string>
            {
                [HeaderKeyId] = keyId,
                [HeaderTimestamp] = timestamp,
                [HeaderNonce] = nonce,
                [HeaderSignature] = signature
            };
        }

        /// <summary>
        /// 验签(入站方调用)。成功返回 true;失败返回 false 并通过 <paramref name="failureReason"/> 给出原因。
        /// </summary>
        public static bool TryVerify(
            string secret,
            Func<string, string?> header,
            string method, string path, string rawQuery, byte[] body,
            out string failureReason)
        {
            var keyId = (header(HeaderKeyId) ?? string.Empty).Trim();
            var timestamp = (header(HeaderTimestamp) ?? string.Empty).Trim();
            var nonce = (header(HeaderNonce) ?? string.Empty).Trim();
            var signature = (header(HeaderSignature) ?? string.Empty).Trim();
            if (keyId.Length == 0 || timestamp.Length == 0 || nonce.Length == 0 || signature.Length == 0)
            {
                failureReason = "缺少服务签名头";
                return false;
            }

            if (!long.TryParse(timestamp, out var ts))
            {
                failureReason = "签名时间戳非法";
                return false;
            }
            var skew = Math.Abs(DateTimeOffset.UtcNow.ToUnixTimeSeconds() - ts);
            if (skew > MaxSkewSeconds)
            {
                failureReason = "签名已过期或时钟偏移过大";
                return false;
            }

            EvictExpiredNonces();
            if (SeenNonces.Count >= MaxNonceEntries)
            {
                failureReason = "签名校验暂不可用";
                return false;
            }

            var canonical = Canonical(keyId, timestamp, nonce, method, path, rawQuery, body);
            var expected = HmacBase64(secret, canonical);
            if (!FixedTimeEquals(expected, signature))
            {
                failureReason = "服务签名不匹配";
                return false;
            }

            var nonceExpiry = DateTimeOffset.UtcNow.ToUnixTimeSeconds() + NonceTtlSeconds;
            if (!SeenNonces.TryAdd(nonce, nonceExpiry))
            {
                failureReason = "签名已被使用(重放)";
                return false;
            }

            failureReason = string.Empty;
            return true;
        }

        /// <summary>是否带有签名头(用于"双接受"迁移判定)。</summary>
        public static bool HasSignatureHeaders(Func<string, string?> header)
        {
            var sig = header(HeaderSignature);
            return !string.IsNullOrWhiteSpace(sig);
        }

        // ---- 纯密码学 ----

        internal static string Canonical(
            string keyId, string timestamp, string nonce, string method, string path, string rawQuery, byte[] body)
        {
            return string.Join("\n",
                keyId,
                timestamp,
                nonce,
                (method ?? string.Empty).ToUpperInvariant(),
                path ?? string.Empty,
                rawQuery ?? string.Empty,
                HexSha256(body));
        }

        internal static string HmacBase64(string secret, string canonical)
        {
            using var mac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
            var raw = mac.ComputeHash(Encoding.UTF8.GetBytes(canonical));
            return Convert.ToBase64String(raw);
        }

        private static string HexSha256(byte[] body)
        {
            using var sha = SHA256.Create();
            var digest = sha.ComputeHash(body ?? Array.Empty<byte>());
            var sb = new StringBuilder(digest.Length * 2);
            foreach (var b in digest)
            {
                sb.Append(b.ToString("x2"));
            }
            return sb.ToString();
        }

        private static bool FixedTimeEquals(string a, string b)
        {
            return CryptographicOperations.FixedTimeEquals(
                Encoding.UTF8.GetBytes(a), Encoding.UTF8.GetBytes(b));
        }

        private static void EvictExpiredNonces()
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            foreach (var entry in SeenNonces)
            {
                if (entry.Value < now)
                {
                    SeenNonces.TryRemove(entry.Key, out _);
                }
            }
        }
    }
}
