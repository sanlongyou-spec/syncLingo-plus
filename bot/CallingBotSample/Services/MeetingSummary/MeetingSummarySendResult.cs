using System.Collections.Generic;

namespace CallingBotSample.Services.MeetingSummary
{
    /// <summary>
    /// Result of sending a meeting summary to explicit Teams users.
    /// </summary>
    public class MeetingSummarySendResult
    {
        public List<MeetingSummarySentRecipient> Sent { get; } = new List<MeetingSummarySentRecipient>();

        public List<MeetingSummaryFailedRecipient> Failed { get; } = new List<MeetingSummaryFailedRecipient>();
    }

    /// <summary>
    /// Teams user that received a summary.
    /// </summary>
    public class MeetingSummarySentRecipient
    {
        public string Recipient { get; set; } = string.Empty;

        public string AadId { get; set; } = string.Empty;

        public string? DisplayName { get; set; }

        public string? Email { get; set; }
    }

    /// <summary>
    /// Teams user that failed to receive a summary.
    /// </summary>
    public class MeetingSummaryFailedRecipient
    {
        public string Recipient { get; set; } = string.Empty;

        public string Error { get; set; } = string.Empty;
    }
}
