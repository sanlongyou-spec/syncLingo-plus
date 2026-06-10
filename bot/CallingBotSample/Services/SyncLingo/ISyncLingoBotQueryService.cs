using System;
using System.Threading;
using System.Threading.Tasks;

namespace CallingBotSample.Services.SyncLingo
{
    public interface ISyncLingoBotQueryService
    {
        Task<SyncLingoBotQueryResponse> QueryAsync(TeamsBotUserContext userContext, string message, CancellationToken cancellationToken);

        Task StreamQueryAsync(TeamsBotUserContext userContext, string message, Func<string, Task> chunkCallback, CancellationToken cancellationToken);
    }

    public class TeamsBotUserContext
    {
        public string? AadId { get; set; }

        public string? Mail { get; set; }

        public string? UserPrincipalName { get; set; }

        public string? DisplayName { get; set; }
    }
}
