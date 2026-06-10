// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System;
using System.Linq;
using System.Net;
using System.Threading.Tasks;
using CallingBotSample.Cache;
using CallingBotSample.Options;
using CallingBotSample.Services.MicrosoftGraph;
using CallingBotSample.Utility;
using CallingMeetingBot.Extensions;
using Microsoft.AspNetCore.Http;
using Microsoft.Bot.Builder;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Microsoft.Graph;
using Microsoft.Graph.Communications.Client.Authentication;
using Microsoft.Graph.Communications.Common.Telemetry;
using Microsoft.Graph.Communications.Core.Notifications;
using Microsoft.Graph.Communications.Core.Serialization;

namespace CallingBotSample.Bots
{
    public class CallingBot : ActivityHandler
    {
        private readonly IGraphLogger graphLogger;
        private readonly IRequestAuthenticationProvider authenticationProvider;
        private readonly INotificationProcessor notificationProcessor;
        private readonly CommsSerializer serializer;
        private readonly BotOptions botOptions;
        private readonly ICallService callService;
        private readonly ICallCache callCache;
        private readonly ILogger<CallingBot> logger;

        public CallingBot(
            ICallService callService,
            IGraphLogger graphLogger,
            ICallCache callCache,
            IOptions<BotOptions> botOptions,
            ILogger<CallingBot> logger)
        {
            this.botOptions = botOptions.Value;
            this.callService = callService;
            this.graphLogger = graphLogger;
            this.callCache = callCache;
            this.logger = logger;

            var name = this.GetType().Assembly.GetName().Name;
            authenticationProvider = new Authentication.AuthenticationProvider(name, this.botOptions.AppId, this.botOptions.AppSecret, graphLogger);

            serializer = new CommsSerializer();
            notificationProcessor = new NotificationProcessor(serializer);
            notificationProcessor.OnNotificationReceived += this.NotificationProcessor_OnNotificationReceived;
        }

        public async Task ProcessNotificationAsync(HttpRequest request, HttpResponse response)
        {
            try
            {
                var httpRequest = request.CreateRequestMessage();
                var results = await authenticationProvider.ValidateInboundRequestAsync(httpRequest).ConfigureAwait(false);
                if (results.IsValid)
                {
                    var httpResponse = await notificationProcessor.ProcessNotificationAsync(httpRequest).ConfigureAwait(false);
                    await httpResponse.CreateHttpResponseAsync(response).ConfigureAwait(false);
                }
                else
                {
                    response.StatusCode = StatusCodes.Status403Forbidden;
                }
            }
            catch (Exception e)
            {
                response.StatusCode = (int)HttpStatusCode.InternalServerError;
                await response.WriteAsync(e.ToString()).ConfigureAwait(false);
            }
        }

        private void NotificationProcessor_OnNotificationReceived(NotificationEventArgs args)
        {
            _ = NotificationProcessor_OnNotificationReceivedAsync(args).ForgetAndLogExceptionAsync(
              graphLogger,
              $"Error processing notification {args.Notification.ResourceUrl} with scenario {args.ScenarioId}");
        }

        private async Task NotificationProcessor_OnNotificationReceivedAsync(NotificationEventArgs args)
        {
            graphLogger.CorrelationId = args.ScenarioId;
            var callId = GetCallIdFromNotification(args);

            if (args.ResourceData is Call call)
            {
                logger.LogInformation(
                    "Call notification. callId={CallId}, changeType={ChangeType}, state={State}",
                    callId, args.ChangeType, call.State);

                if (args.ChangeType == ChangeType.Created && call.State == CallState.Incoming)
                {
                    await callService.Answer(callId, null);
                }
                else if (args.ChangeType == ChangeType.Updated && call.State == CallState.Established)
                {
                    if (!callCache.GetIsEstablished(callId))
                    {
                        callCache.SetIsEstablished(callId);
                        callCache.SetActiveCallId(callId);
                        logger.LogInformation("Call established. callId={CallId}", callId);
                    }
                }
            }
            else if (args.IsParticipantsNotification() && args.ResourceData is object[] objs)
            {
                Participant[] participants = Array.ConvertAll(objs, (object o) => (Participant)o);
                logger.LogInformation(
                    "Participants notification. callId={CallId}, count={Count}",
                    callId, participants.Length);

                // Replace the cached list with the CURRENT roster (this notification carries the
                // full participant collection), so users who left the meeting are removed instead
                // of lingering forever.
                var currentAadIds = participants
                    .Where(p => p.Info?.Identity?.User?.Id != null)
                    .Select(p => p.Info.Identity.User.Id)
                    .ToList();
                callCache.SetParticipantAadIds(callId, currentAadIds);

                bool atLeastOneUserJoined = callCache.GetAtLeastOneUserJoined(callId);

                if (!atLeastOneUserJoined && participants.Any(p => p.Info?.Identity?.User != null))
                {
                    callCache.SetAtLeastOneUserJoined(callId);
                }

                if (participants.Length == 1 &&
                    participants[0]?.Info?.Identity?.Application?.Id == botOptions.AppId &&
                    atLeastOneUserJoined)
                {
                    logger.LogInformation("Only bot remains; hanging up. callId={CallId}", callId);
                    await callService.HangUp(callId);
                }
            }
        }

        private string GetCallIdFromNotification(NotificationEventArgs args)
        {
            if (args.ResourceData is CommsOperation op && !string.IsNullOrEmpty(op.ClientContext))
                return op.ClientContext;
            return args.Notification.ResourceUrl.Split('/')[3];
        }
    }
}
