using Microsoft.Bot.Schema;

namespace CallingBotSample.Cache
{
    public interface IConversationReferenceCache
    {
        void SetConversationReference(string aadId, ConversationReference reference);

        ConversationReference? GetConversationReference(string aadId);
    }
}
