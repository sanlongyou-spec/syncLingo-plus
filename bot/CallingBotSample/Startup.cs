// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using CallingBotSample.Bots;
using CallingBotSample.Cache;
using CallingBotSample.Options;
using CallingBotSample.Services.BotFramework;
using CallingBotSample.Services.MicrosoftGraph;
using CallingBotSample.Services.MeetingSummary;
using CallingBotSample.Services.SyncLingo;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Bot.Builder;
using Microsoft.Bot.Builder.Integration.AspNet.Core;
using Microsoft.Bot.Connector.Authentication;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Graph.Communications.Common.Telemetry;

namespace CallingBotSample
{
    public class Startup
    {
        private readonly GraphLogger logger;

        public Startup(IConfiguration configuration)
        {
            Configuration = configuration;
            this.logger = new GraphLogger(typeof(Startup).Assembly.GetName().Name);
        }

        public IConfiguration Configuration { get; }

        public void ConfigureServices(IServiceCollection services)
        {
            services.AddControllers();
            services.AddOptions();

            services.AddSingleton<IGraphLogger>(this.logger);
            services.AddSingleton<BotFrameworkAuthentication, ConfigurationBotFrameworkAuthentication>();
            services.AddSingleton<IBotFrameworkHttpAdapter, AdapterWithErrorHandler>();

            services.AddTransient<IBot, MessageBot>();
            services.AddTransient<CallingBot>();

            services.Configure<AzureAdOptions>(Configuration.GetSection("AzureAd"));
            services.Configure<BotOptions>(Configuration.GetSection("Bot"));
            services.Configure<SummaryFileStorageOptions>(Configuration.GetSection("SummaryFileStorage"));

            services.AddMicrosoftGraphServices(options => Configuration.Bind("AzureAd", options));

            services.AddSingleton<IConnectorClientFactory, ConnectorClientFactory>();
            services.AddScoped<IMeetingSummaryService, MeetingSummaryService>();
            services.AddScoped<ISharePointFileStorageService, SharePointFileStorageService>();
            services.AddHttpClient<ISyncLingoBotQueryService, SyncLingoBotQueryService>();
            services.AddCaches();
        }

        public void Configure(IApplicationBuilder app, IWebHostEnvironment env)
        {
            if (env.IsDevelopment())
                app.UseDeveloperExceptionPage();

            app.UseCookiePolicy();
            app.UseDefaultFiles()
               .UseStaticFiles()
               .UseWebSockets()
               .UseRouting()
               .UseAuthorization()
               .UseEndpoints(endpoints => endpoints.MapControllers());
        }
    }
}
