using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading.Tasks;
using CallingBotSample.Services.MicrosoftGraph;
using Microsoft.Extensions.Logging;
using Microsoft.Graph;

namespace CallingBotSample.Services.MeetingSummary
{
    public class MeetingSummaryService : IMeetingSummaryService
    {
        private readonly IChatService chatService;
        private readonly ILogger<MeetingSummaryService> logger;

        public MeetingSummaryService(
            IChatService chatService,
            ILogger<MeetingSummaryService> logger)
        {
            this.chatService = chatService;
            this.logger = logger;
        }

        public async Task<MeetingSummarySendResult> SendSummaryAsync(string htmlContent, IReadOnlyCollection<string> recipients)
        {
            var normalizedRecipients = recipients
                .Where(recipient => !string.IsNullOrWhiteSpace(recipient))
                .Select(recipient => recipient.Trim())
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();

            if (normalizedRecipients.Count == 0)
                throw new ArgumentException("At least one Teams user recipient is required.", nameof(recipients));

            logger.LogInformation("[MeetingSummaryService] Send summary to Teams users start, recipientCount={Count}",
                normalizedRecipients.Count);

            var stopwatch = Stopwatch.StartNew();
            var deliveries = await Task.WhenAll(normalizedRecipients.Select(recipient => SendToRecipientAsync(recipient, htmlContent)));
            var result = new MeetingSummarySendResult();
            foreach (var delivery in deliveries)
            {
                if (delivery.Sent != null)
                    result.Sent.Add(delivery.Sent);
                if (delivery.Failed != null)
                    result.Failed.Add(delivery.Failed);
            }

            stopwatch.Stop();
            logger.LogInformation(
                "[MeetingSummaryService] Send summary to Teams users end, sentCount={SentCount}, failedCount={FailedCount}, elapsedMs={ElapsedMs}",
                result.Sent.Count, result.Failed.Count, stopwatch.ElapsedMilliseconds);

            return result;
        }

        private async Task<(MeetingSummarySentRecipient? Sent, MeetingSummaryFailedRecipient? Failed)> SendToRecipientAsync(
            string recipient,
            string htmlContent)
        {
            try
            {
                var target = await chatService.SendMessageToUserAsync(recipient, htmlContent);
                logger.LogInformation("[MeetingSummaryService] Summary sent to Teams user. recipient={Recipient}, aadId={AadId}",
                    recipient, target.AadId);
                return (new MeetingSummarySentRecipient
                {
                    Recipient = recipient,
                    AadId = target.AadId,
                    DisplayName = target.DisplayName,
                    Email = target.Email
                }, null);
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "[MeetingSummaryService] Failed to send summary to Teams user. recipient={Recipient}",
                    recipient);
                return (null, new MeetingSummaryFailedRecipient
                {
                    Recipient = recipient,
                    Error = ex.Message
                });
            }
        }

        public Task SendToMeetingChatAsync(string threadId, string htmlContent)
            => chatService.SendToMeetingChatAsync(threadId, htmlContent);
    }
}
