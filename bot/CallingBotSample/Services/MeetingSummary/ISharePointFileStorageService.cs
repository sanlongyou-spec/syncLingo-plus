// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System.Threading.Tasks;

namespace CallingBotSample.Services.MeetingSummary
{
    public interface ISharePointFileStorageService
    {
        Task<SharePointFileLink> UploadSummaryFileAsync(string fileName, string contentType, byte[] bytes);
    }
}
