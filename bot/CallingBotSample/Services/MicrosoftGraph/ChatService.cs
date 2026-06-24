// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Security.Claims;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using CallingBotSample.Cache;
using CallingBotSample.Options;
using Microsoft.Bot.Builder;
using Microsoft.Bot.Builder.Integration.AspNet.Core;
using Microsoft.Bot.Schema;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Microsoft.Graph;

namespace CallingBotSample.Services.MicrosoftGraph
{
    public class ChatService : IChatService
    {
        private readonly GraphServiceClient graphServiceClient;
        private readonly AzureAdOptions azureAdOptions;
        private readonly BotOptions botOptions;
        private readonly IBotFrameworkHttpAdapter adapter;
        private readonly IConversationReferenceCache conversationReferenceCache;
        private readonly ILogger<ChatService> logger;

        public ChatService(
            GraphServiceClient graphServiceClient,
            IOptions<AzureAdOptions> azureAdOptions,
            IOptions<BotOptions> botOptions,
            IBotFrameworkHttpAdapter adapter,
            IConversationReferenceCache conversationReferenceCache,
            ILogger<ChatService> logger)
        {
            this.graphServiceClient = graphServiceClient;
            this.azureAdOptions = azureAdOptions.Value;
            this.botOptions = botOptions.Value;
            this.adapter = adapter;
            this.conversationReferenceCache = conversationReferenceCache;
            this.logger = logger;
        }

        /// <inheritdoc/>
        public async Task<TeamsUserMessageTarget> SendMessageToUserAsync(string userIdentifier, string htmlContent)
        {
            var sw = Stopwatch.StartNew();
            logger.LogInformation("[ChatService] SendMessageToUser start, identifier={Id}", userIdentifier);

            // Try to resolve display info; failures are non-fatal.
            var target = new TeamsUserMessageTarget { Input = userIdentifier, AadId = userIdentifier };
            try
            {
                target = await ResolveTeamsUserAsync(userIdentifier);
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "[ChatService] Graph user lookup failed, sending to raw AAD ID={Id}", userIdentifier);
            }

            // Fast path: reuse ConversationReference from when the user last messaged the bot.
            // This is the most reliable proactive messaging approach because the ServiceUrl and
            // conversation ID come directly from Teams, not from Graph API inference.
            var cachedRef = conversationReferenceCache.GetConversationReference(target.AadId);
            if (cachedRef != null)
            {
                logger.LogInformation("[ChatService] Using cached ConversationReference for aadId={AadId}", target.AadId);
                await SendViaAdapterAsync(cachedRef, htmlContent);
                sw.Stop();
                logger.LogInformation("[ChatService] SendMessageToUser end (cached path), aadId={AadId}, ms={Ms}", target.AadId, sw.ElapsedMilliseconds);
                return target;
            }

            // Fallback: install bot for user → trigger conversationUpdate → poll for the stored ref.
            // This follows the official Microsoft sample (graph-proactive-installation):
            // install app → Teams fires conversationUpdate → OnMembersAddedAsync stores ref → send.
            logger.LogInformation("[ChatService] No cached ConversationReference, running install+poll flow for aadId={AadId}", target.AadId);
            EnsureCatalogAppIdConfigured();

            var installId = await InstallBotForUserAsync(target.AadId);

            // 直接用 GET /chat 返回的 1:1 会话(chat.Id)构造会话引用并发送，不再依赖那个
            // 常常不回投的 conversationUpdate 事件(实测安装后该事件永远不到，导致超时失败)。
            var chat = await GetInstalledAppChatAsync(target.AadId, installId);
            if (!string.IsNullOrEmpty(chat?.Id))
            {
                var directRef = BuildReferenceFromChat(chat.Id, target.AadId);
                logger.LogInformation("[ChatService] Sending via chat-id direct reference, aadId={AadId}, chatId={ChatId}",
                    target.AadId, chat.Id);
                await SendViaAdapterAsync(directRef, htmlContent);
                conversationReferenceCache.SetConversationReference(target.AadId, directRef);
                sw.Stop();
                logger.LogInformation("[ChatService] SendMessageToUser end (chat-id path), aadId={AadId}, ms={Ms}",
                    target.AadId, sw.ElapsedMilliseconds);
                return target;
            }

            // 兜底：拿不到 chat.Id 时，再尝试轮询 conversationUpdate 捕获的引用。
            var polledRef = await PollConversationReferenceAsync(target.AadId, maxWaitMs: 8000);
            if (polledRef == null)
                throw new InvalidOperationException(
                    $"Bot installed for user {target.AadId} but could not obtain a 1:1 chat reference (chat id missing). " +
                    "Ask the user to open a chat with the bot once in Teams.");

            await SendViaAdapterAsync(polledRef, htmlContent);
            sw.Stop();
            logger.LogInformation("[ChatService] SendMessageToUser end (poll fallback path), aadId={AadId}, ms={Ms}",
                target.AadId, sw.ElapsedMilliseconds);
            return target;
        }

        private void EnsureCatalogAppIdConfigured()
        {
            if (string.IsNullOrWhiteSpace(botOptions.CatalogAppId) || botOptions.CatalogAppId.StartsWith("<<"))
            {
                throw new InvalidOperationException(
                    "Bot:CatalogAppId is required for Teams user messages. Publish the Teams app to the org catalog, set CatalogAppId, and grant TeamsAppInstallation.ReadWriteSelfForUser.All.");
            }
        }

        private Task SendViaAdapterAsync(ConversationReference convRef, string htmlContent)
            => ExecuteContinueConversation(CreateProactiveReference(convRef), htmlContent);

        private async Task ExecuteContinueConversation(ConversationReference convRef, string htmlContent)
        {
            logger.LogInformation(
                "[ChatService] ContinueConversation start — convId={ConvId}, isGroup={IsGroup}, tenantId={Tenant}, serviceUrl={Url}, botId={Bot}, userId={User}",
                convRef.Conversation?.Id, convRef.Conversation?.IsGroup, convRef.Conversation?.TenantId,
                convRef.ServiceUrl, convRef.Bot?.Id, convRef.User?.Id);

            // For SingleTenant bots (MicrosoftAppType: SingleTenant in appsettings) the ClaimsIdentity
            // overload must be used so ConfigurationBotFrameworkAuthentication can resolve the correct
            // tenant-specific token endpoint. The string-botId overload omits the 'tid' claim and
            // causes the Bot Framework Channel Service to return HTTP 400.
            var identity = new ClaimsIdentity(new[]
            {
                new Claim("aud", botOptions.AppId!),
                new Claim("appid", botOptions.AppId!),
                new Claim("tid", azureAdOptions.TenantId!),
            });

            Exception? deliveryError = null;
            await ((CloudAdapter)adapter).ContinueConversationAsync(
                identity,
                convRef,
                async (turnContext, cancellationToken) =>
                {
                    try
                    {
                        var text = ConvertHtmlToTeamsMarkdown(htmlContent);
                        var activity = MessageFactory.Text(text);
                        // Teams supports plain, markdown, and xml. "html" is not a valid TextFormat
                        // value and can make Bot Framework return HTTP 400.
                        activity.TextFormat = TextFormatTypes.Markdown;
                        activity.ReplyToId = null;
                        await turnContext.SendActivityAsync(activity, cancellationToken);
                    }
                    catch (Exception ex)
                    {
                        // Log the raw response body so we can see the exact Bot Service error.
                        var detail = ex.GetType().Name + ": " + ex.Message;
                        if (ex is Microsoft.Rest.HttpOperationException httpEx)
                            detail += $" | HTTP {(int?)httpEx.Response?.StatusCode} | body: {httpEx.Response?.Content}";
                        logger.LogError("[ChatService] SendActivityAsync failed: {Detail}", detail);
                        deliveryError = ex;
                        throw;
                    }
                },
                CancellationToken.None);

            if (deliveryError != null)
                throw deliveryError;

            logger.LogInformation("[ChatService] Message sent via adapter. conversationId={ConvId}", convRef.Conversation?.Id);
        }

        private ConversationReference CreateProactiveReference(ConversationReference source)
        {
            var botAccount = source.Bot;
            if (botAccount == null || string.IsNullOrWhiteSpace(botAccount.Id))
            {
                botAccount = new ChannelAccount { Id = $"28:{botOptions.AppId}" };
            }

            var conversation = source.Conversation == null
                ? null
                : new ConversationAccount
                {
                    Id = source.Conversation.Id,
                    IsGroup = source.Conversation.IsGroup,
                    Name = source.Conversation.Name,
                    ConversationType = source.Conversation.ConversationType,
                    AadObjectId = source.Conversation.AadObjectId,
                    Role = source.Conversation.Role,
                    TenantId = source.Conversation.TenantId ?? azureAdOptions.TenantId
                };

            return new ConversationReference
            {
                ActivityId = null,
                User = source.User,
                Bot = botAccount,
                Conversation = conversation,
                ChannelId = source.ChannelId ?? "msteams",
                Locale = source.Locale,
                ServiceUrl = string.IsNullOrWhiteSpace(source.ServiceUrl)
                    ? GetTenantServiceUrl()
                    : source.ServiceUrl
            };
        }

        private string GetTenantServiceUrl()
            => $"https://smba.trafficmanager.net/id/{azureAdOptions.TenantId}/";

        private static string ConvertHtmlToTeamsMarkdown(string content)
        {
            if (string.IsNullOrWhiteSpace(content))
                return string.Empty;

            var text = content;
            text = Regex.Replace(text, "(?i)<br\\s*/?>", "\n");
            text = Regex.Replace(text, "(?i)</p\\s*>", "\n\n");
            text = Regex.Replace(text, "(?i)<li\\s*>", "- ");
            text = Regex.Replace(text, "(?i)</li\\s*>", "\n");
            text = Regex.Replace(text, "<[^>]+>", string.Empty);
            text = WebUtility.HtmlDecode(text);
            text = Regex.Replace(text, "\\n{3,}", "\n\n");
            return text.Trim();
        }

        private async Task<TeamsUserMessageTarget> ResolveTeamsUserAsync(string userIdentifier)
        {
            var id = userIdentifier.Trim();
            User? user = null;
            try
            {
                user = await graphServiceClient.Users[id]
                    .Request()
                    .Select("id,displayName,mail,userPrincipalName")
                    .GetAsync();
            }
            catch (ServiceException ex) when (ex.StatusCode == HttpStatusCode.NotFound)
            {
                logger.LogDebug("[ChatService] Direct user lookup 404, trying filter. id={Id}", id);
            }

            if (user == null)
            {
                var escaped = id.Replace("'", "''");
                var page = await graphServiceClient.Users
                    .Request()
                    .Filter($"mail eq '{escaped}' or userPrincipalName eq '{escaped}'")
                    .Select("id,displayName,mail,userPrincipalName")
                    .Top(1)
                    .GetAsync();
                user = page.CurrentPage.FirstOrDefault();
            }

            if (string.IsNullOrEmpty(user?.Id))
                throw new InvalidOperationException($"Teams user not found: {id}");

            return new TeamsUserMessageTarget
            {
                Input = userIdentifier,
                AadId = user.Id,
                DisplayName = user.DisplayName,
                Email = user.Mail ?? user.UserPrincipalName
            };
        }

        // Install the bot for a user and return the installId.
        // Avoids GET+filter (requires ConsistencyLevel:eventual) — just POST and handle 409.
        private async Task<string> InstallBotForUserAsync(string userAadId)
        {
            var installation = new UserScopeTeamsAppInstallation
            {
                AdditionalData = new Dictionary<string, object>
                {
                    { "teamsApp@odata.bind", $"https://graph.microsoft.com/v1.0/appCatalogs/teamsApps/{botOptions.CatalogAppId}" }
                }
            };

            try
            {
                logger.LogInformation("[ChatService] Installing bot for user {Id}", userAadId);
                var result = await graphServiceClient.Users[userAadId].Teamwork.InstalledApps
                    .Request()
                    .AddAsync(installation);

                if (!string.IsNullOrEmpty(result?.Id))
                    return result.Id;
            }
            catch (ServiceException ex) when ((int)ex.StatusCode == 409)
            {
                logger.LogDebug("[ChatService] Bot already installed for user {Id} (409)", userAadId);
            }

            // Re-query to get installId — use ConsistencyLevel header for navigation property filter.
            var apps = await graphServiceClient.Users[userAadId].Teamwork.InstalledApps
                .Request()
                .Header("ConsistencyLevel", "eventual")
                .Filter($"teamsApp/id eq '{botOptions.CatalogAppId}'")
                .Expand("teamsApp")
                .GetAsync();

            var existing = apps.CurrentPage.FirstOrDefault();
            if (existing?.Id == null)
                throw new InvalidOperationException($"Bot installation not found for user {userAadId} after install attempt.");

            logger.LogInformation("[ChatService] Got installId={IId} for user {Id}", existing.Id, userAadId);
            return existing.Id;
        }

        // GET /users/{id}/teamwork/installedApps/{installId}/chat 返回该用户与机器人的 1:1 会话(含 chat.Id)。
        // 直接用 chat.Id 构造会话引用即可主动发消息，无需等待 conversationUpdate 回投。
        private async Task<Chat?> GetInstalledAppChatAsync(string userAadId, string installId)
        {
            try
            {
                logger.LogInformation("[ChatService] GET installedApp 1:1 chat for user {Id}", userAadId);
                return await graphServiceClient.Users[userAadId].Teamwork.InstalledApps[installId].Chat
                    .Request()
                    .GetAsync();
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "[ChatService] GET /chat failed, user {Id}", userAadId);
                return null;
            }
        }

        // 用 Graph 返回的 1:1 会话 id 直接构造 ConversationReference（personal 1:1）。
        private ConversationReference BuildReferenceFromChat(string chatId, string userAadId)
        {
            return new ConversationReference
            {
                ChannelId = "msteams",
                ServiceUrl = GetTenantServiceUrl(),
                Bot = new ChannelAccount { Id = $"28:{botOptions.AppId}" },
                Conversation = new ConversationAccount
                {
                    Id = chatId,
                    IsGroup = false,
                    ConversationType = "personal",
                    TenantId = azureAdOptions.TenantId,
                },
                User = new ChannelAccount { AadObjectId = userAadId },
            };
        }

        // Poll the cache until OnMembersAddedAsync stores a ConversationReference, or timeout.
        private async Task<ConversationReference?> PollConversationReferenceAsync(string aadId, int maxWaitMs)
        {
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < maxWaitMs)
            {
                var convRef = conversationReferenceCache.GetConversationReference(aadId);
                if (convRef != null)
                {
                    logger.LogInformation("[ChatService] ConversationReference available after {Ms}ms for aadId={AadId}", sw.ElapsedMilliseconds, aadId);
                    return convRef;
                }
                await Task.Delay(500);
            }
            logger.LogWarning("[ChatService] Timed out waiting for ConversationReference, aadId={AadId}", aadId);
            return null;
        }
    }
}
