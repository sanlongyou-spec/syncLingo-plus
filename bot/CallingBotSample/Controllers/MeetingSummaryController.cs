using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using CallingBotSample.Cache;
using CallingBotSample.Helpers;
using CallingBotSample.Services.MeetingSummary;
using CallingBotSample.Services.MicrosoftGraph;
#pragma warning disable CS4014 // fire-and-forget is intentional
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;
using Microsoft.Graph;

namespace CallingBotSample.Controllers
{
    [ApiController]
    [Route("api/meetings")]
    public class MeetingSummaryController : ControllerBase
    {
        private readonly IMeetingSummaryService meetingSummaryService;
        private readonly ICallService callService;
        private readonly ICallCache callCache;
        private readonly IChatService chatService;
        private readonly GraphServiceClient graphServiceClient;
        private readonly ISharePointFileStorageService sharePointFileStorageService;
        private readonly ILogger<MeetingSummaryController> logger;

        public MeetingSummaryController(
            IMeetingSummaryService meetingSummaryService,
            ICallService callService,
            ICallCache callCache,
            IChatService chatService,
            GraphServiceClient graphServiceClient,
            ISharePointFileStorageService sharePointFileStorageService,
            ILogger<MeetingSummaryController> logger)
        {
            this.meetingSummaryService = meetingSummaryService;
            this.callService = callService;
            this.callCache = callCache;
            this.chatService = chatService;
            this.graphServiceClient = graphServiceClient;
            this.sharePointFileStorageService = sharePointFileStorageService;
            this.logger = logger;
        }

        /// <summary>
        /// Let the bot join a Teams meeting via its join URL.
        /// Kept for the Teams calling PoC; summary delivery does not depend on this call.
        ///
        /// POST /api/meetings/join
        /// { "meetingUrl": "https://teams.microsoft.com/l/meetup-join/..." }
        /// </summary>
        [HttpPost("join")]
        public async Task<IActionResult> JoinMeeting([FromBody] JoinMeetingRequest request)
        {
            if (string.IsNullOrWhiteSpace(request.MeetingUrl))
                return BadRequest(new { error = "meetingUrl is required" });

            if (!JoinInfo.IsFullJoinUrl(request.MeetingUrl))
                return BadRequest(new { error = "Please provide the full Teams join URL (must contain /l/meetup-join/). Copy it from the calendar invite or meeting details." });

            try
            {
                var chatInfo = JoinInfo.ParseChatInfo(request.MeetingUrl);
                var meetingInfo = JoinInfo.ParseMeetingInfo(request.MeetingUrl);
                var activeCallId = callCache.GetActiveCallId();
                if (!string.IsNullOrWhiteSpace(activeCallId))
                {
                    var activeThreadId = callCache.GetMeetingThreadId(activeCallId);
                    if (string.Equals(activeThreadId, chatInfo.ThreadId, StringComparison.OrdinalIgnoreCase))
                    {
                        logger.LogInformation(
                            "Bot is already in the requested meeting. callId={CallId}, threadId={ThreadId}",
                            activeCallId,
                            chatInfo.ThreadId);
                        var activeMeetingTitle = await WaitForMeetingTitleAsync(chatInfo.ThreadId);
                        return Ok(new { callId = activeCallId, threadId = chatInfo.ThreadId, meetingTitle = activeMeetingTitle });
                    }

                    logger.LogInformation(
                        "Bot is leaving the previous meeting before joining another. callId={CallId}, threadId={ThreadId}",
                        activeCallId,
                        activeThreadId);
                    try
                    {
                        await callService.HangUp(activeCallId);
                    }
                    catch (ServiceException ex)
                    {
                        logger.LogWarning(ex, "Failed to hang up previous call; continuing with the requested meeting. callId={CallId}", activeCallId);
                    }
                }

                var call = await callService.Create(chatInfo, meetingInfo);

                callCache.SetActiveCallId(call.Id);
                callCache.SetMeetingThreadId(call.Id, chatInfo.ThreadId);

                logger.LogInformation("Bot joined meeting via REST. callId={CallId}, threadId={ThreadId}", call.Id, chatInfo.ThreadId);

                // Install bot in meeting chat before returning so send failures are visible in logs.
                // If the app is already installed, the send path can still use the threadId fallback.
                await chatService.EnsureBotInMeetingChatAsync(chatInfo.ThreadId);

                var meetingTitle = await WaitForMeetingTitleAsync(chatInfo.ThreadId);
                return Ok(new { callId = call.Id, threadId = chatInfo.ThreadId, meetingTitle });
            }
            catch (System.ArgumentException ex)
            {
                logger.LogWarning(ex, "Failed to parse meeting URL: {Url}", request.MeetingUrl);
                return BadRequest(new { error = ex.Message });
            }
            catch (Microsoft.Graph.ServiceException ex)
            {
                logger.LogError(ex, "Graph API error while joining meeting");
                return StatusCode(502, new { error = $"Graph API error: {ex.Message}" });
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Unexpected error while joining meeting");
                return StatusCode(500, new { error = $"加入会议失败: {ex.Message}" });
            }
        }

        /// <summary>
        /// Send a summary to explicit Teams users.
        ///
        /// POST /api/meetings/summary
        /// { "content": "...", "recipients": ["someone@example.com"] }
        /// </summary>
        [HttpPost("summary")]
        public async Task<IActionResult> SendSummary([FromBody] SendSummaryRequest request)
        {
            if (string.IsNullOrWhiteSpace(request.Content))
                return BadRequest(new { error = "content is required" });

            var recipients = (request.Recipients ?? new List<string>())
                .Where(recipient => !string.IsNullOrWhiteSpace(recipient))
                .Select(recipient => recipient.Trim())
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();
            if (recipients.Count == 0)
            {
                logger.LogWarning("[MeetingSummaryController] SendSummary rejected: no Teams user recipients.");
                return BadRequest(new { error = "请至少填写一个 Teams 用户邮箱或 AAD ID。" });
            }

            try
            {
                var result = await meetingSummaryService.SendSummaryAsync(request.Content, recipients);
                var firstFailure = result.Failed.FirstOrDefault()?.Error;
                var response = new
                {
                    sent = result.Sent.Count > 0,
                    sentCount = result.Sent.Count,
                    failedCount = result.Failed.Count,
                    recipients = result.Sent,
                    failures = result.Failed,
                    error = result.Sent.Count == 0
                        ? firstFailure ?? "No Teams users received the summary."
                        : null
                };

                if (result.Sent.Count == 0)
                    return StatusCode(502, response);

                return Ok(response);
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "[MeetingSummaryController] SendSummary to Teams users failed.");
                return StatusCode(502, new { error = $"发送失败: {ex.Message}" });
            }
        }

        /// <summary>
        /// Receive a generated PDF, upload it to SharePoint, and send a Teams message with the
        /// SharePoint link to the chosen user(s) and/or the active meeting chat.
        ///
        /// POST /api/meetings/summary-file  (multipart/form-data)
        ///   file: the .pdf; fileName; title?; recipients[]?; sendToChat=true|false
        /// </summary>
        [HttpPost("summary-file")]
        public async Task<IActionResult> SendSummaryFile(
            [FromForm] IFormFile file,
            [FromForm] string? fileName,
            [FromForm] string? title,
            [FromForm] List<string>? recipients,
            [FromForm] bool sendToChat)
        {
            if (file == null || file.Length == 0)
                return BadRequest(new { error = "file is required" });

            byte[] bytes;
            using (var ms = new MemoryStream())
            {
                await file.CopyToAsync(ms);
                bytes = ms.ToArray();
            }
            var name = string.IsNullOrWhiteSpace(fileName) ? (file.FileName ?? "会议总结.pdf") : fileName;
            var contentType = string.IsNullOrWhiteSpace(file.ContentType)
                ? "application/pdf"
                : file.ContentType;

            var cleaned = (recipients ?? new List<string>())
                .Where(r => !string.IsNullOrWhiteSpace(r)).Select(r => r.Trim())
                .Distinct(StringComparer.OrdinalIgnoreCase).ToList();

            int userSent = 0, userFailed = 0;
            string? chatThreadId = null;
            string? firstError = null;
            SharePointFileLink? fileLink = null;
            try
            {
                logger.LogInformation(
                    "[MeetingSummaryController] SendSummaryFile start. fileName={FileName}, bytes={Bytes}, recipients={RecipientCount}, sendToChat={SendToChat}",
                    name, bytes.Length, cleaned.Count, sendToChat);

                fileLink = await sharePointFileStorageService.UploadSummaryFileAsync(name, contentType, bytes);
                var summaryTitle = string.IsNullOrWhiteSpace(title) ? name : title;
                var content =
                    $"📄 会议总结：{summaryTitle}\n\n"
                    + "文件已上传到 Teams/SharePoint，可点击下面链接查看或下载：\n\n"
                    + $"[打开 PDF]({fileLink.WebUrl})\n\n"
                    + "说明：该文件为 syncLingo 自动生成的会议总结 PDF。";

                if (cleaned.Count > 0)
                {
                    var result = await meetingSummaryService.SendSummaryAsync(content, cleaned);
                    userSent = result.Sent.Count;
                    userFailed = result.Failed.Count;
                    firstError = result.Failed.FirstOrDefault()?.Error;
                }
                if (sendToChat)
                {
                    var callId = callCache.GetActiveCallId();
                    var threadId = string.IsNullOrEmpty(callId) ? null : callCache.GetMeetingThreadId(callId);
                    if (string.IsNullOrEmpty(threadId))
                        firstError ??= "没有活跃会议或未找到会议聊天线程";
                    else
                    {
                        await meetingSummaryService.SendToMeetingChatAsync(threadId, content);
                        chatThreadId = threadId;
                    }
                }
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "[MeetingSummaryController] SendSummaryFile failed.");
                return StatusCode(502, new { error = $"上传 SharePoint 或发送 Teams 失败: {ex.Message}" });
            }

            bool anySent = userSent > 0 || chatThreadId != null;
            logger.LogInformation(
                "[MeetingSummaryController] SendSummaryFile done. sent={Sent}, userSent={UserSent}, userFailed={UserFailed}, chatThreadId={ChatThreadId}, driveItemId={DriveItemId}",
                anySent, userSent, userFailed, chatThreadId, fileLink?.DriveItemId);

            return Ok(new
            {
                sent = anySent,
                userSent,
                userFailed,
                chatThreadId,
                downloadUrl = fileLink?.WebUrl,
                sharePointFileName = fileLink?.FileName,
                driveItemId = fileLink?.DriveItemId,
                error = anySent ? null : firstError
            });
        }

        /// <summary>
        /// Send a user-confirmed meeting notification to the given Teams users.
        /// POST /api/meetings/notification  { content, recipients[] }
        /// </summary>
        [HttpPost("notification")]
        public async Task<IActionResult> SendNotification([FromBody] MeetingNotificationRequest request)
        {
            var recipients = (request.Recipients ?? new List<string>())
                .Where(r => !string.IsNullOrWhiteSpace(r)).Select(r => r.Trim())
                .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
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
                return Ok(new
                {
                    sent = result.Sent.Count > 0,
                    sentCount = result.Sent.Count,
                    failedCount = result.Failed.Count,
                    error = result.Sent.Count == 0 ? result.Failed.FirstOrDefault()?.Error : null
                });
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "[MeetingSummaryController] SendNotification failed.");
                return StatusCode(502, new { error = $"发送会议通知失败: {ex.Message}" });
            }
        }

        /// <summary>
        /// Post the summary to the active meeting's group chat thread.
        /// All participants in the meeting will see it without any pre-setup.
        ///
        /// POST /api/meetings/summary/chat
        /// { "content": "..." }
        /// </summary>
        [HttpPost("summary/chat")]
        public async Task<IActionResult> SendSummaryToMeetingChat([FromBody] SendSummaryToChatRequest request)
        {
            if (string.IsNullOrWhiteSpace(request.Content))
                return BadRequest(new { error = "content is required" });

            var callId = callCache.GetActiveCallId();
            if (string.IsNullOrEmpty(callId))
                return BadRequest(new { error = "没有活跃的会议，请先让 bot 加入会议" });

            var threadId = callCache.GetMeetingThreadId(callId);
            if (string.IsNullOrEmpty(threadId))
                return BadRequest(new { error = "未找到会议聊天线程，请重新加入会议" });

            try
            {
                await meetingSummaryService.SendToMeetingChatAsync(threadId, request.Content);
                logger.LogInformation("[MeetingSummaryController] Summary sent to meeting chat. threadId={ThreadId}", threadId);
                return Ok(new { sent = true, threadId });
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "[MeetingSummaryController] SendSummaryToMeetingChat failed. threadId={ThreadId}", threadId);
                return StatusCode(502, new { error = $"发送到会议聊天失败: {ex.Message}" });
            }
        }

        /// <summary>
        /// Get current meeting participants with display names and emails resolved via Graph.
        /// GET /api/meetings/participants
        /// </summary>
        [HttpGet("participants")]
        public async Task<IActionResult> GetParticipants()
        {
            var callId = callCache.GetActiveCallId();
            if (string.IsNullOrEmpty(callId))
                return Ok(new { callId = (string?)null, participants = System.Array.Empty<object>() });

            var aadIds = callCache.GetParticipantAadIds(callId);
            var threadId = callCache.GetMeetingThreadId(callId);

            var tasks = aadIds.Select(async aadId =>
            {
                try
                {
                    var user = await graphServiceClient.Users[aadId].Request()
                        .Select("displayName,mail,userPrincipalName")
                        .GetAsync();
                    return new { aadId, displayName = (string?)user.DisplayName, email = (string?)(user.Mail ?? user.UserPrincipalName) };
                }
                catch (Exception ex)
                {
                    logger.LogWarning(ex, "Failed to resolve Graph user for aadId={AadId}", aadId);
                    return new { aadId, displayName = (string?)null, email = (string?)null };
                }
            });

            var participants = await Task.WhenAll(tasks);
            var meetingTitle = !string.IsNullOrWhiteSpace(threadId)
                ? callCache.GetMeetingTitle(threadId)
                : null;
            return Ok(new { callId, threadId, meetingTitle, participants });
        }

        private async Task<string?> WaitForMeetingTitleAsync(string threadId)
        {
            for (var attempt = 0; attempt < 6; attempt++)
            {
                var title = callCache.GetMeetingTitle(threadId);
                if (!string.IsNullOrWhiteSpace(title))
                {
                    return title;
                }

                await Task.Delay(500);
            }

            return null;
        }
    }

    public class JoinMeetingRequest
    {
        public string MeetingUrl { get; set; } = string.Empty;
    }

    public class SendSummaryRequest
    {
        public string Content { get; set; } = string.Empty;
        public List<string> Recipients { get; set; } = new List<string>();
    }

    public class SendSummaryToChatRequest
    {
        public string Content { get; set; } = string.Empty;
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
