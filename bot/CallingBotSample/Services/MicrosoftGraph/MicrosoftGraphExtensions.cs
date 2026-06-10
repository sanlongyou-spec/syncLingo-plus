// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System;
using Azure.Identity;
using CallingBotSample.Options;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Graph;

namespace CallingBotSample.Services.MicrosoftGraph
{
    public static class MicrosoftGraphExtensions
    {
        public static IServiceCollection AddMicrosoftGraphServices(this IServiceCollection services, Action<AzureAdOptions> azureAdOptionsAction)
        {
            var options = new AzureAdOptions();
            azureAdOptionsAction(options);

            var credential = new ClientSecretCredential(options.TenantId, options.ClientId, options.ClientSecret);

            services.AddScoped<GraphServiceClient>(_ => new GraphServiceClient(credential));
            services.AddTransient<ICallService, CallService>();
            services.AddTransient<IChatService, ChatService>();

            return services;
        }
    }
}
