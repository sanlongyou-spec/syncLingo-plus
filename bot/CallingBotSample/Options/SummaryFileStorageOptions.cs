// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

namespace CallingBotSample.Options
{
    /// <summary>
    /// SharePoint document library used to store generated syncLingo summary PDFs.
    /// </summary>
    public class SummaryFileStorageOptions
    {
        public string Mode { get; set; } = "SharePoint";
        public string? SiteId { get; set; }
        public string? SiteUrl { get; set; }
        public string? DriveId { get; set; }
        public string DriveName { get; set; } = "Documents";
        public string FolderPath { get; set; } = "syncLingo/会议总结";
        public string LinkType { get; set; } = "view";
        public string LinkScope { get; set; } = "organization";
    }
}
