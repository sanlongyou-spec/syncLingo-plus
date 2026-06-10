// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System;
using System.Collections.Generic;
using Microsoft.Bot.Schema;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Logging;

namespace CallingBotSample.Cache
{
    public class CallCache : ICallCache
    {
        private readonly IMemoryCache cache;
        private readonly ILogger<CallCache> logger;

        private const string AtLeastOneUserJoinedKey = "atLeastOneUserJoined:";
        private const string IsEstablishedKey = "established:";
        private const string BackendSessionKey = "backendSession:";
        private const string ActiveCallIdKey = "activeCallId";
        private const string MeetingThreadIdKey = "meetingThreadId:";
        private const string MeetingTitleKey = "meetingTitle:";
        private const string ParticipantsKey = "participants:";
        private const string ConvRefKey = "convRef:";
        private const string MeetingChatConvRefKey = "meetingChatConvRef:";

        public CallCache(IMemoryCache cache, ILogger<CallCache> logger)
        {
            this.cache = cache;
            this.logger = logger;
        }

        /// <inheritdoc />
        public bool GetIsEstablished(string callId)
        {
            return cache.Get<bool>(IsEstablishedKey + callId);
        }

        /// <inheritdoc />
        public void SetIsEstablished(string callId, bool isEstablished = true)
        {
            cache.Set(IsEstablishedKey + callId, isEstablished, new MemoryCacheEntryOptions
            {
                AbsoluteExpirationRelativeToNow = TimeSpan.FromSeconds(30)
            });
        }

        /// <inheritdoc />
        public bool GetAtLeastOneUserJoined(string callId)
        {
            return cache.Get<bool>(AtLeastOneUserJoinedKey + callId);
        }

        /// <inheritdoc />
        public void SetAtLeastOneUserJoined(string callId, bool hasAtLeastOneUserJoined = true)
        {
            cache.Set(AtLeastOneUserJoinedKey + callId, hasAtLeastOneUserJoined, new MemoryCacheEntryOptions
            {
                // This 1 hour cache is sufficient for this sample.
                // If you are replicating this code, you might want to consider an alternative value which takes into account
                // the meeting's scheduled length.
                AbsoluteExpirationRelativeToNow = TimeSpan.FromHours(1)
            });
        }

        public string? GetBackendSessionId(string callId)
        {
            return cache.Get<string>(BackendSessionKey + callId);
        }

        public void SetBackendSessionId(string callId, string sessionId)
        {
            cache.Set(BackendSessionKey + callId, sessionId, new MemoryCacheEntryOptions
            {
                AbsoluteExpirationRelativeToNow = TimeSpan.FromHours(4)
            });
        }

        public void RemoveBackendSessionId(string callId)
        {
            cache.Remove(BackendSessionKey + callId);
        }

        public string? GetActiveCallId()
        {
            return cache.Get<string>(ActiveCallIdKey);
        }

        public void SetActiveCallId(string callId)
        {
            cache.Set(ActiveCallIdKey, callId, new MemoryCacheEntryOptions
            {
                AbsoluteExpirationRelativeToNow = TimeSpan.FromHours(4)
            });
        }

        public string? GetMeetingThreadId(string callId)
        {
            return cache.Get<string>(MeetingThreadIdKey + callId);
        }

        public void SetMeetingThreadId(string callId, string threadId)
        {
            cache.Set(MeetingThreadIdKey + callId, threadId, new MemoryCacheEntryOptions
            {
                AbsoluteExpirationRelativeToNow = TimeSpan.FromHours(4)
            });
        }

        public string? GetMeetingTitle(string threadId)
        {
            return cache.Get<string>(MeetingTitleKey + threadId);
        }

        public void SetMeetingTitle(string threadId, string title)
        {
            if (string.IsNullOrWhiteSpace(threadId) || string.IsNullOrWhiteSpace(title))
            {
                return;
            }

            cache.Set(MeetingTitleKey + threadId, title.Trim(), new MemoryCacheEntryOptions
            {
                AbsoluteExpirationRelativeToNow = TimeSpan.FromHours(8)
            });
            logger.LogInformation("[CallCache] Stored meeting title, threadId={ThreadId}, title={Title}", threadId, title.Trim());
        }

        public IReadOnlyList<string> GetParticipantAadIds(string callId)
        {
            return cache.Get<List<string>>(ParticipantsKey + callId) ?? new List<string>();
        }

        public void AddParticipantAadId(string callId, string aadId)
        {
            var list = cache.GetOrCreate(ParticipantsKey + callId, entry =>
            {
                entry.AbsoluteExpirationRelativeToNow = TimeSpan.FromHours(4);
                return new List<string>();
            })!;
            lock (list)
            {
                if (!list.Contains(aadId))
                    list.Add(aadId);
            }
        }

        // Replace the cached participant list with the current roster from a participants
        // notification, so people who LEFT the meeting are removed (notifications carry the
        // full current participant collection, not a delta).
        public void SetParticipantAadIds(string callId, IEnumerable<string> aadIds)
        {
            var list = new List<string>();
            foreach (var id in aadIds)
            {
                if (!string.IsNullOrEmpty(id) && !list.Contains(id))
                    list.Add(id);
            }
            cache.Set(ParticipantsKey + callId, list, new MemoryCacheEntryOptions
            {
                AbsoluteExpirationRelativeToNow = TimeSpan.FromHours(4)
            });
        }

        public ConversationReference? GetConversationReference(string aadId)
            => cache.Get<ConversationReference>(ConvRefKey + aadId);

        public void SetConversationReference(string aadId, ConversationReference reference)
            => cache.Set(ConvRefKey + aadId, reference, new MemoryCacheEntryOptions
                { AbsoluteExpirationRelativeToNow = TimeSpan.FromDays(7) });

        public ConversationReference? GetMeetingChatConversationReference(string threadId)
            => cache.Get<ConversationReference>(MeetingChatConvRefKey + threadId);

        public void SetMeetingChatConversationReference(string threadId, ConversationReference reference)
            => cache.Set(MeetingChatConvRefKey + threadId, reference, new MemoryCacheEntryOptions
                { AbsoluteExpirationRelativeToNow = TimeSpan.FromHours(8) });
    }
}
