// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System;

namespace CallingBotSample.Options
{
    public class BotOptions
    {
        public string? AppId { get; set; }
        /// <summary>
        /// The Teams app catalog ID (assigned when the app is published to the org catalog).
        /// Required for auto-installing the bot into Teams user personal scope.
        /// Leave empty to skip auto-install.
        /// </summary>
        public string? CatalogAppId { get; set; }

        /// <summary>
        /// Base URL of the syncLingo Java backend used by Teams conversational queries.
        /// </summary>
        public Uri? BackendBaseUrl { get; set; }

        /// <summary>
        /// Optional shared secret sent to the syncLingo Java backend for Teams Bot queries.
        /// </summary>
        public string? BackendApiSecret { get; set; }

        /// <summary>
        /// P4 服务签名:Java→C#(下行)验签密钥。与 Java 的 SERVICE_SIGNATURE_DOWNSTREAM_KEY 同值。留空=不校验。
        /// </summary>
        public string? ServiceSignatureDownstreamKey { get; set; }

        /// <summary>
        /// 配置下行密钥后是否强制要求 Java→C# 请求携带签名。默认 true;迁移放行需显式设 false。
        /// </summary>
        public bool RequireServiceSignatureDownstream { get; set; } = true;

        /// <summary>
        /// P4 服务签名:C#→Java(上行)签名密钥。与 Java 的 SERVICE_SIGNATURE_UPSTREAM_KEY 同值。留空=不签名。
        /// </summary>
        public string? ServiceSignatureUpstreamKey { get; set; }
    }
}
