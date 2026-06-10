// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System;
using System.Collections.Concurrent;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
using System.Threading.Tasks;
using Azure.Core;
using Azure.Identity;
using CallingBotSample.Options;
using Microsoft.Bot.Connector;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Microsoft.Rest;

namespace CallingBotSample.Services.BotFramework
{
    /// <inheritdoc/>
    public class ConnectorClientFactory : IConnectorClientFactory
    {
        private readonly BotOptions botOptions;
        private readonly AzureAdOptions azureAdOptions;
        private readonly ConcurrentDictionary<string, ConnectorClient> connectorClients = new ConcurrentDictionary<string, ConnectorClient>();
        private readonly ILogger<ConnectorClientFactory> logger;

        public ConnectorClientFactory(
            IOptions<BotOptions> botOptions,
            IOptions<AzureAdOptions> azureAdOptions,
            ILogger<ConnectorClientFactory> logger)
        {
            this.botOptions = botOptions.Value;
            this.azureAdOptions = azureAdOptions.Value;
            this.logger = logger;
        }

        /// <inheritdoc/>
        public ConnectorClient CreateConnectorClient(string? serviceUrl = null)
        {
            // Use tenant-specific endpoint; the generic /teams/ URL rejects proactive creates.
            var url = new Uri(serviceUrl ?? $"https://smba.trafficmanager.net/id/{azureAdOptions.TenantId}/");
            var clientKey = $"{url}:{botOptions.AppId}";

            return connectorClients.GetOrAdd(clientKey, _ =>
            {
                // MicrosoftAppCredentials defaults to botframework.com tenant, which rejects
                // single-tenant app registrations. Use Azure.Identity directly so the token
                // is always fetched from the correct tenant endpoint.
                var credentials = new BotFrameworkTokenCredentials(
                    azureAdOptions.TenantId!,
                    azureAdOptions.ClientId!,
                    azureAdOptions.ClientSecret!);

                return new ConnectorClient(url, credentials, new HttpClient());
            });
        }

        // Acquires Bot Framework tokens via Azure.Identity (tenant-specific endpoint).
        // Azure.Identity handles token caching and refresh automatically.
        private sealed class BotFrameworkTokenCredentials : ServiceClientCredentials
        {
            private static readonly string[] Scopes = { "https://api.botframework.com/.default" };
            private readonly ClientSecretCredential credential;

            public BotFrameworkTokenCredentials(string tenantId, string clientId, string clientSecret)
            {
                credential = new ClientSecretCredential(tenantId, clientId, clientSecret);
            }

            public override async Task ProcessHttpRequestAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            {
                var token = await credential.GetTokenAsync(new TokenRequestContext(Scopes), cancellationToken);
                request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token.Token);
                await base.ProcessHttpRequestAsync(request, cancellationToken);
            }
        }
    }
}
