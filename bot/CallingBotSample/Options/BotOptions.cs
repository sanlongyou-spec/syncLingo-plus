// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System;

namespace CallingBotSample.Options
{
    public class BotOptions
    {
        public string? AppId { get; set; }
        public string? AppSecret { get; set; }
        public Uri? BotBaseUrl { get; set; }
        public Uri? PlaceCallEndpointUrl { get; set; }
        public string? GraphApiResourceUrl { get; set; }
        public string? MicrosoftLoginUrl { get; set; }
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
    }
}
