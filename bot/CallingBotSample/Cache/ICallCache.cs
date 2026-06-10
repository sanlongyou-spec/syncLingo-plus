// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System.Collections.Generic;
using Microsoft.Bot.Schema;

namespace CallingBotSample.Cache
{
    public interface ICallCache
    {
        void SetConversationReference(string aadId, ConversationReference reference);
        ConversationReference? GetConversationReference(string aadId);

        /// <summary>
        /// If the at least one user has joined the call
        /// </summary>
        /// <param name="callId">The call's ID</param>
        /// <returns>Whether the a user has joined the call</returns>
        bool GetAtLeastOneUserJoined(string callId);

        /// <summary>
        /// Set's whether a user has joined a call
        /// </summary>
        /// <param name="callId">The call's ID</param>
        /// <param name="hasAtLeastOneUserJoined"></param>
        /// <returns></returns>
        void SetAtLeastOneUserJoined(string callId, bool hasAtLeastOneUserJoined = true);

        /// <summary>
        ///
        /// </summary>
        /// <param name="callId">The call's ID</param>
        /// <returns>Whether the call has been established</returns>
        bool GetIsEstablished(string callId);

        /// <summary>
        /// Set's if the call has been established
        /// </summary>
        /// <param name="callId">The call's ID</param>
        /// <param name="isEstablished"></param>
        /// <returns></returns>
        void SetIsEstablished(string callId, bool isEstablished = true);

        string? GetBackendSessionId(string callId);

        void SetBackendSessionId(string callId, string sessionId);

        void RemoveBackendSessionId(string callId);

        string? GetActiveCallId();

        void SetActiveCallId(string callId);

        string? GetMeetingThreadId(string callId);

        void SetMeetingThreadId(string callId, string threadId);

        string? GetMeetingTitle(string threadId);

        void SetMeetingTitle(string threadId, string title);

        IReadOnlyList<string> GetParticipantAadIds(string callId);

        void AddParticipantAadId(string callId, string aadId);

        /// <summary>
        /// Replace the cached participant list with the current roster (removes people who left).
        /// </summary>
        void SetParticipantAadIds(string callId, IEnumerable<string> aadIds);

        /// <summary>
        /// Store the ConversationReference for the meeting group chat thread.
        /// Captured from OnMembersAddedAsync after the bot is installed in the chat.
        /// </summary>
        void SetMeetingChatConversationReference(string threadId, ConversationReference reference);

        ConversationReference? GetMeetingChatConversationReference(string threadId);
    }
}
