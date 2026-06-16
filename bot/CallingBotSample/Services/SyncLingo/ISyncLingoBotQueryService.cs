using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace CallingBotSample.Services.SyncLingo
{
    public interface ISyncLingoBotQueryService
    {
        Task<SyncLingoBotQueryResponse> QueryAsync(
            TeamsBotUserContext userContext,
            string message,
            IReadOnlyList<SyncLingoBotChatTurn>? history,
            CancellationToken cancellationToken);

        Task StreamQueryAsync(
            TeamsBotUserContext userContext,
            string message,
            IReadOnlyList<SyncLingoBotChatTurn>? history,
            Func<string, Task> chunkCallback,
            CancellationToken cancellationToken);
    }

    public class TeamsBotUserContext
    {
        public string? AadId { get; set; }

        public string? Mail { get; set; }

        public string? UserPrincipalName { get; set; }

        public string? DisplayName { get; set; }
    }

    public class SyncLingoBotChatTurn
    {
        public string Role { get; set; } = "user";

        public string Content { get; set; } = string.Empty;
    }
}
