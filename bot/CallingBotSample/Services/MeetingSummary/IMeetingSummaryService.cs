using System.Collections.Generic;
using System.Threading.Tasks;

namespace CallingBotSample.Services.MeetingSummary
{
    public interface IMeetingSummaryService
    {
        /// <summary>
        /// Send a summary message to explicit Teams users.
        /// </summary>
        /// <param name="htmlContent">HTML-formatted summary content</param>
        /// <param name="recipients">Teams user identifiers, such as AAD IDs or user principal names</param>
        Task<MeetingSummarySendResult> SendSummaryAsync(string htmlContent, IReadOnlyCollection<string> recipients);

        /// <summary>
        /// Post a summary message to the meeting group chat thread.
        /// </summary>
        Task SendToMeetingChatAsync(string threadId, string htmlContent);
    }
}
