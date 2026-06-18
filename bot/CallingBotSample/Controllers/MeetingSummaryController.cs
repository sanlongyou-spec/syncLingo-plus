using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using CallingBotSample.Services.MeetingSummary;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;

namespace CallingBotSample.Controllers
{
    [ApiController]
    [Route("api/meetings")]
    public class MeetingSummaryController : ControllerBase
    {
        private readonly IMeetingSummaryService meetingSummaryService;
        private readonly ILogger<MeetingSummaryController> logger;

        public MeetingSummaryController(
            IMeetingSummaryService meetingSummaryService,
            ILogger<MeetingSummaryController> logger)
        {
            this.meetingSummaryService = meetingSummaryService;
            this.logger = logger;
        }

        /// <summary>
        /// Send meeting text to explicit Teams users.
        ///
        /// POST /api/meetings/summary
        /// { "content": "...", "recipients": ["someone@example.com"] }
        /// </summary>
        [HttpPost("summary")]
        public async Task<IActionResult> SendSummary([FromBody] SendSummaryRequest request)
        {
            if (string.IsNullOrWhiteSpace(request.Content))
                return BadRequest(new { error = "content is required" });

            var recipients = NormalizeRecipients(request.Recipients);
            if (recipients.Count == 0)
            {
                logger.LogWarning("[MeetingSummaryController] SendSummary rejected: no Teams user recipients.");
                return BadRequest(new { error = "请至少填写一个 Teams 用户邮箱或 AAD ID。" });
            }

            try
            {
                var result = await meetingSummaryService.SendSummaryAsync(request.Content, recipients);
                return DeliveryResponse(result);
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "[MeetingSummaryController] SendSummary to Teams users failed.");
                return DeliveryFailureResponse(recipients, $"发送失败: {ex.Message}");
            }
        }

        /// <summary>
        /// Send a user-confirmed meeting notification to explicit Teams users.
        ///
        /// POST /api/meetings/notification
        /// { "content": "...", "recipients": ["someone@example.com"] }
        /// </summary>
        [HttpPost("notification")]
        public async Task<IActionResult> SendNotification([FromBody] MeetingNotificationRequest request)
        {
            var recipients = NormalizeRecipients(request.Recipients);
            if (recipients.Count == 0)
                return BadRequest(new { error = "请至少提供一个 Teams 用户邮箱" });

            var content = string.IsNullOrWhiteSpace(request.Content)
                ? $"**📅 会议通知：{request.MeetingName}**\n\n"
                    + $"🕐 时间：{(string.IsNullOrWhiteSpace(request.MeetingTime) ? "（未提供）" : request.MeetingTime)}\n\n"
                    + (string.IsNullOrWhiteSpace(request.MeetingUrl)
                        ? ""
                        : $"[▶ 点击加入会议]({request.MeetingUrl})")
                : request.Content.Trim();
            try
            {
                var result = await meetingSummaryService.SendSummaryAsync(content, recipients);
                return DeliveryResponse(result);
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "[MeetingSummaryController] SendNotification failed.");
                return DeliveryFailureResponse(recipients, $"发送会议通知失败: {ex.Message}");
            }
        }

        private static List<string> NormalizeRecipients(IEnumerable<string>? recipients)
        {
            return (recipients ?? new List<string>())
                .Where(recipient => !string.IsNullOrWhiteSpace(recipient))
                .Select(recipient => recipient.Trim())
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();
        }

        private IActionResult DeliveryResponse(MeetingSummarySendResult result)
        {
            var firstFailure = result.Failed.FirstOrDefault()?.Error;
            var response = new
            {
                sent = result.Sent.Count > 0,
                sentCount = result.Sent.Count,
                failedCount = result.Failed.Count,
                recipients = result.Sent,
                failures = result.Failed,
                error = result.Sent.Count == 0
                    ? firstFailure ?? "No Teams users received the message."
                    : null
            };

            if (result.Sent.Count == 0)
                return StatusCode(502, response);

            return Ok(response);
        }

        private IActionResult DeliveryFailureResponse(IReadOnlyCollection<string> recipients, string message)
        {
            return StatusCode(502, new
            {
                sent = false,
                sentCount = 0,
                failedCount = recipients.Count,
                recipients = Array.Empty<object>(),
                failures = recipients.Select(recipient => new { recipient, error = message }).ToList(),
                error = message
            });
        }
    }

    public class SendSummaryRequest
    {
        public string Content { get; set; } = string.Empty;
        public List<string> Recipients { get; set; } = new List<string>();
    }

    public class MeetingNotificationRequest
    {
        public string Content { get; set; } = string.Empty;
        public string MeetingName { get; set; } = string.Empty;
        public string MeetingTime { get; set; } = string.Empty;
        public string MeetingUrl { get; set; } = string.Empty;
        public List<string> Recipients { get; set; } = new List<string>();
    }
}
