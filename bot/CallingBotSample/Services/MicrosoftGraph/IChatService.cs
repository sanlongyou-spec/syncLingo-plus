// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System.Threading.Tasks;
namespace CallingBotSample.Services.MicrosoftGraph
{
    public interface IChatService
    {
        /// <summary>
        /// Send a Teams text/Markdown message to a Teams user via 1:1 chat.
        /// </summary>
        /// <param name="userIdentifier">AAD ID, user principal name, or mail address</param>
        /// <param name="htmlContent">Message content; simple HTML is normalized to Teams Markdown.</param>
        Task<TeamsUserMessageTarget> SendMessageToUserAsync(string userIdentifier, string htmlContent);

    }

    /// <summary>
    /// Resolved Teams user message target.
    /// </summary>
    public class TeamsUserMessageTarget
    {
        public string Input { get; set; } = string.Empty;

        public string AadId { get; set; } = string.Empty;

        public string? DisplayName { get; set; }

        public string? Email { get; set; }

        public string? ChatId { get; set; }
    }
}
