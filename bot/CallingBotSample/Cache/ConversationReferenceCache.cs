using System;
using Microsoft.Bot.Schema;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Logging;

namespace CallingBotSample.Cache
{
    public class ConversationReferenceCache : IConversationReferenceCache
    {
        private const string ConversationReferenceKey = "conversationReference:";

        private readonly IMemoryCache cache;
        private readonly ILogger<ConversationReferenceCache> logger;

        public ConversationReferenceCache(IMemoryCache cache, ILogger<ConversationReferenceCache> logger)
        {
            this.cache = cache;
            this.logger = logger;
        }

        public ConversationReference? GetConversationReference(string aadId)
            => cache.Get<ConversationReference>(ConversationReferenceKey + aadId);

        public void SetConversationReference(string aadId, ConversationReference reference)
        {
            if (string.IsNullOrWhiteSpace(aadId))
                return;

            cache.Set(ConversationReferenceKey + aadId, reference, new MemoryCacheEntryOptions
            {
                AbsoluteExpirationRelativeToNow = TimeSpan.FromDays(7)
            });
            logger.LogDebug("[ConversationReferenceCache] Stored conversation reference, aadId={AadId}", aadId);
        }
    }
}
