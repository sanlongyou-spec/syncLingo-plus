// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using CallingBotSample.Cache;
using CallingBotSample.Services.SyncLingo;
using Microsoft.Bot.Builder;
using Microsoft.Bot.Builder.Teams;
using Microsoft.Bot.Schema;
using Microsoft.Extensions.Logging;

namespace CallingBotSample.Bots
{
    public class MessageBot : TeamsActivityHandler
    {
        private const int MaxHistoryTurns = 6;
        private const int MaxHistoryChars = 1200;

        private static readonly ConcurrentDictionary<string, Queue<SyncLingoBotChatTurn>> ChatHistory =
            new ConcurrentDictionary<string, Queue<SyncLingoBotChatTurn>>();

        private static readonly HashSet<string> CommandPrefixes = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "help", "hi", "hello", "帮助", "菜单", "说明", "？", "?",
            "你可以做什么", "能做什么", "有什么功能", "怎么用", "使用说明", "使用帮助",
            "查看纪要", "查看摘要", "查看总结",
            "最近", "历史", "list", "history", "最近会议", "历史会议", "会议记录",
            "查看最近", "查看历史", "查看最近会议", "查看历史会议",
            "摘要", "总结", "纪要", "summary",
            "搜索", "查", "查询", "search"
        };

        private readonly ISyncLingoBotQueryService syncLingoBotQueryService;
        private readonly ICallCache callCache;
        private readonly ILogger<MessageBot> logger;

        public MessageBot(
            ISyncLingoBotQueryService syncLingoBotQueryService,
            ICallCache callCache,
            ILogger<MessageBot> logger)
        {
            this.syncLingoBotQueryService = syncLingoBotQueryService;
            this.callCache = callCache;
            this.logger = logger;
        }

        protected override async Task OnMembersAddedAsync(
            IList<ChannelAccount> membersAdded,
            ITurnContext<IConversationUpdateActivity> turnContext,
            CancellationToken cancellationToken)
        {
            // When the bot is installed in a personal or group chat, Teams fires conversationUpdate.
            // Capture the ConversationReference so it can be used for proactive messaging later.
            var convRef = turnContext.Activity.GetConversationReference();
            var conversationId = turnContext.Activity.Conversation?.Id;

            if (turnContext.Activity.Conversation?.IsGroup == true && !string.IsNullOrEmpty(conversationId))
            {
                // Group chat or meeting chat — store by thread/conversation ID.
                callCache.SetMeetingChatConversationReference(conversationId, convRef);
                logger.LogInformation("[MessageBot] Stored meeting chat ConversationReference, conversationId={Id}", conversationId);
                await TryCacheMeetingTitleAsync(turnContext, conversationId, cancellationToken);
            }
            else
            {
                // 1:1 personal chat — store by the user's AAD ID (the non-bot member).
                foreach (var member in membersAdded)
                {
                    if (member.Id != turnContext.Activity.Recipient.Id && !string.IsNullOrEmpty(member.AadObjectId))
                    {
                        callCache.SetConversationReference(member.AadObjectId, convRef);
                        logger.LogInformation("[MessageBot] Stored personal ConversationReference for aadId={AadId}", member.AadObjectId);
                    }
                }
            }

        }

        protected override async Task OnMessageActivityAsync(
            ITurnContext<IMessageActivity> turnContext,
            CancellationToken cancellationToken)
        {
            var message = NormalizeMessage(turnContext.Activity.Text);
            var userContext = await ResolveTeamsUserContextAsync(turnContext, cancellationToken);
            logger.LogInformation("[MessageBot] Message received, aadId={AadId}, upn={Upn}, messageLen={MessageLen}",
                userContext.AadId, userContext.UserPrincipalName, message.Length);

            if (!string.IsNullOrEmpty(userContext.AadId))
            {
                callCache.SetConversationReference(userContext.AadId, turnContext.Activity.GetConversationReference());
                logger.LogDebug("[MessageBot] Stored ConversationReference for aadId={AadId}", userContext.AadId);
            }

            var conversationId = turnContext.Activity.Conversation?.Id;
            if (turnContext.Activity.Conversation?.IsGroup == true && !string.IsNullOrEmpty(conversationId))
            {
                callCache.SetMeetingChatConversationReference(conversationId, turnContext.Activity.GetConversationReference());
                await TryCacheMeetingTitleAsync(turnContext, conversationId, cancellationToken);
            }

            if (string.IsNullOrWhiteSpace(message))
            {
                await turnContext.SendActivityAsync(
                    MessageFactory.Text("可以发送【最近】【搜索 关键词】【摘要 会议ID】查询记录，或直接提问让 AI 从历史会议中检索答案。"),
                    cancellationToken);
                return;
            }

            var historyKey = BuildHistoryKey(turnContext, userContext);
            var history = SnapshotHistory(historyKey);
            var response = await syncLingoBotQueryService.QueryAsync(userContext, message, history, cancellationToken);
            RememberHistory(historyKey, message, response.ReplyText);
            await SendBotResponseAsync(turnContext, response, cancellationToken);
        }

        private static string BuildHistoryKey(ITurnContext<IMessageActivity> turnContext, TeamsBotUserContext userContext)
        {
            return FirstNonBlank(
                turnContext.Activity.Conversation?.Id,
                userContext.AadId,
                userContext.UserPrincipalName,
                userContext.Mail,
                "unknown");
        }

        private static IReadOnlyList<SyncLingoBotChatTurn> SnapshotHistory(string key)
        {
            if (!ChatHistory.TryGetValue(key, out var queue))
                return Array.Empty<SyncLingoBotChatTurn>();
            lock (queue)
            {
                return queue
                    .Select(t => new SyncLingoBotChatTurn { Role = t.Role, Content = t.Content })
                    .ToList();
            }
        }

        private static void RememberHistory(string key, string userMessage, string? assistantMessage)
        {
            if (string.IsNullOrWhiteSpace(key) || string.IsNullOrWhiteSpace(userMessage))
                return;
            var queue = ChatHistory.GetOrAdd(key, _ => new Queue<SyncLingoBotChatTurn>());
            lock (queue)
            {
                queue.Enqueue(new SyncLingoBotChatTurn
                {
                    Role = "user",
                    Content = TruncateForHistory(userMessage)
                });
                if (!string.IsNullOrWhiteSpace(assistantMessage))
                {
                    queue.Enqueue(new SyncLingoBotChatTurn
                    {
                        Role = "assistant",
                        Content = TruncateForHistory(NormalizeAnswerText(assistantMessage))
                    });
                }
                while (queue.Count > MaxHistoryTurns)
                    queue.Dequeue();
            }
        }

        private static string TruncateForHistory(string text)
        {
            var normalized = Regex.Replace(text ?? string.Empty, "\\s+", " ").Trim();
            return normalized.Length <= MaxHistoryChars
                ? normalized
                : normalized.Substring(0, MaxHistoryChars);
        }

        private static async Task SendBotResponseAsync(
            ITurnContext<IMessageActivity> turnContext,
            SyncLingoBotQueryResponse response,
            CancellationToken cancellationToken)
        {
            var sources = response.Sources ?? new List<SyncLingoBotQuerySource>();
            if (sources.Count == 0)
            {
                await turnContext.SendActivityAsync(
                    MessageFactory.Text(NormalizeAnswerText(response.ReplyText)),
                    cancellationToken);
                return;
            }

            await turnContext.SendActivityAsync(
                MessageFactory.Attachment(BuildAnswerCard(response, sources)),
                cancellationToken);
        }

        private static Attachment BuildAnswerCard(
            SyncLingoBotQueryResponse response,
            IReadOnlyList<SyncLingoBotQuerySource> sources)
        {
            var body = new List<object>
            {
                new
                {
                    type = "TextBlock",
                    text = Truncate(NormalizeAnswerText(response.ReplyText), 4500),
                    wrap = true,
                    size = "Default"
                }
            };

            body.Add(new
            {
                type = "TextBlock",
                text = "引用来源",
                weight = "Bolder",
                spacing = "Medium"
            });

            // Dedup by the displayed title so the same document isn't listed multiple times.
            var sourceTitles = sources
                .Select(SourceText)
                .Where(t => !string.IsNullOrWhiteSpace(t))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .Take(6);
            foreach (var title in sourceTitles)
            {
                body.Add(new
                {
                    type = "TextBlock",
                    text = title,
                    wrap = true,
                    spacing = "Small",
                    isSubtle = true
                });
            }

            return new Attachment
            {
                ContentType = "application/vnd.microsoft.card.adaptive",
                Content = new
                {
                    type = "AdaptiveCard",
                    version = "1.4",
                    body
                }
            };
        }

        private static string SourceText(SyncLingoBotQuerySource source)
        {
            // Only show the source title — i.e. the file/document name (SourceName), not the generic
            // type label (Title = "文件内容"/"文件总结") and no meeting / date / snippet.
            return FirstNonBlank(source.SourceName, source.MeetingTitle, source.Title, "会议资料");
        }

        private static string FirstNonBlank(params string?[] values)
        {
            foreach (var value in values)
            {
                if (!string.IsNullOrWhiteSpace(value))
                    return value;
            }
            return string.Empty;
        }

        private static string Truncate(string text, int maxChars)
        {
            if (string.IsNullOrEmpty(text) || text.Length <= maxChars)
                return text;
            return text.Substring(0, maxChars) + "...";
        }

        // Adaptive Card / Teams message markdown renders heading lines (#, ##, ...) at larger sizes,
        // which makes the answer's font size look inconsistent. Convert headings to bold so the whole
        // answer renders at a single, consistent size.
        private static string NormalizeAnswerText(string? text)
        {
            if (string.IsNullOrWhiteSpace(text))
                return "没有可返回的会议记录内容。";

            // "## 标题" -> "**标题**"
            var normalized = Regex.Replace(text, @"(?m)^[ \t]*#{1,6}[ \t]+(.+?)[ \t]*$", "**$1**");
            // Drop any stray leading heading markers that weren't followed by a space.
            normalized = Regex.Replace(normalized, @"(?m)^[ \t]*#{1,6}(?=\S)", string.Empty);
            return normalized.Trim();
        }

        private static bool IsKnownCommand(string message)
        {
            var lower = message.Trim().ToLowerInvariant();
            if (CommandPrefixes.Contains(lower)) return true;
            // Split by ASCII space (works for English commands like "search keyword")
            var spaceIdx = lower.IndexOf(' ');
            var firstWord = spaceIdx > 0 ? lower.Substring(0, spaceIdx) : lower;
            if (CommandPrefixes.Contains(firstWord)) return true;
            // Chinese text has no spaces — check if message starts with any known prefix
            // e.g. "总结班长的战争..." starts with "总结"
            return CommandPrefixes.Any(p => lower.StartsWith(p));
        }

        private static string NormalizeMessage(string? text)
        {
            if (string.IsNullOrWhiteSpace(text))
                return string.Empty;

            var decoded = WebUtility.HtmlDecode(text);
            decoded = Regex.Replace(decoded, "<at>.*?</at>", string.Empty, RegexOptions.IgnoreCase);
            decoded = Regex.Replace(decoded, "<[^>]+>", " ");
            decoded = Regex.Replace(decoded, "\\s+", " ");
            return decoded.Trim();
        }

        private async Task TryCacheMeetingTitleAsync(
            ITurnContext turnContext,
            string conversationId,
            CancellationToken cancellationToken)
        {
            try
            {
                var meetingInfo = await TeamsInfo.GetMeetingInfoAsync(turnContext, null, cancellationToken);
                var title = meetingInfo?.Details?.Title;
                if (string.IsNullOrWhiteSpace(title))
                {
                    logger.LogInformation("[MessageBot] Meeting title is empty, conversationId={ConversationId}", conversationId);
                    return;
                }

                callCache.SetMeetingTitle(conversationId, title);
                logger.LogInformation("[MessageBot] Cached meeting title, conversationId={ConversationId}, title={Title}",
                    conversationId, title);
            }
            catch (System.Exception ex)
            {
                // This can be a regular group chat rather than a meeting chat. Keep the bot flow alive.
                logger.LogInformation(ex, "[MessageBot] Unable to resolve meeting title, conversationId={ConversationId}", conversationId);
            }
        }

        private async Task<TeamsBotUserContext> ResolveTeamsUserContextAsync(
            ITurnContext<IMessageActivity> turnContext,
            CancellationToken cancellationToken)
        {
            var userContext = new TeamsBotUserContext
            {
                DisplayName = turnContext.Activity.From?.Name
            };

            try
            {
                var fromId = turnContext.Activity.From?.Id;
                if (string.IsNullOrEmpty(fromId))
                    return userContext;

                var member = await TeamsInfo.GetMemberAsync(
                    turnContext,
                    fromId,
                    cancellationToken);
                userContext.AadId = member.AadObjectId;
                userContext.Mail = member.Email;
                userContext.UserPrincipalName = member.UserPrincipalName;
                userContext.DisplayName = member.Name ?? userContext.DisplayName;
            }
            catch (System.Exception ex)
            {
                logger.LogWarning(ex, "[MessageBot] Failed to resolve Teams member profile, fromId={FromId}",
                    turnContext.Activity.From?.Id);
            }

            return userContext;
        }
    }
}
