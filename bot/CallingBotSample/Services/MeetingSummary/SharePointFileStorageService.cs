// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using CallingBotSample.Options;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Microsoft.Graph;

namespace CallingBotSample.Services.MeetingSummary
{
    public class SharePointFileStorageService : ISharePointFileStorageService
    {
        private static readonly Regex InvalidFileNameChars = new Regex(@"[\\/:*?""<>|#%{}~&]", RegexOptions.Compiled);
        private static readonly Regex Whitespace = new Regex(@"\s+", RegexOptions.Compiled);

        private readonly GraphServiceClient graphServiceClient;
        private readonly SummaryFileStorageOptions options;
        private readonly ILogger<SharePointFileStorageService> logger;

        public SharePointFileStorageService(
            GraphServiceClient graphServiceClient,
            IOptions<SummaryFileStorageOptions> options,
            ILogger<SharePointFileStorageService> logger)
        {
            this.graphServiceClient = graphServiceClient;
            this.options = options.Value;
            this.logger = logger;
        }

        public async Task<SharePointFileLink> UploadSummaryFileAsync(string fileName, string contentType, byte[] bytes)
        {
            if (bytes == null || bytes.Length == 0)
                throw new ArgumentException("summary file content is empty", nameof(bytes));

            EnsureSharePointMode();

            var driveId = await ResolveDriveIdAsync();
            var safeFileName = BuildUniqueFileName(fileName);
            var folderPath = NormalizePath(options.FolderPath);
            var uploadPath = string.IsNullOrWhiteSpace(folderPath)
                ? safeFileName
                : $"{folderPath}/{safeFileName}";

            logger.LogInformation(
                "[SharePointFileStorage] UploadSummaryFile start, driveId={DriveId}, path={Path}, bytes={Bytes}, contentType={ContentType}",
                driveId, uploadPath, bytes.Length, contentType);

            await EnsureFolderPathAsync(driveId, folderPath);
            DriveItem uploaded;
            using (var stream = new MemoryStream(bytes))
            {
                uploaded = await graphServiceClient.Drives[driveId]
                    .Root
                    .ItemWithPath(uploadPath)
                    .Content
                    .Request()
                    .PutAsync<DriveItem>(stream);
            }

            var webUrl = await CreateOrganizationLinkOrFallbackAsync(driveId, uploaded);
            logger.LogInformation(
                "[SharePointFileStorage] UploadSummaryFile done, driveItemId={DriveItemId}, webUrl={WebUrl}",
                uploaded.Id, webUrl);

            return new SharePointFileLink
            {
                FileName = uploaded.Name ?? safeFileName,
                WebUrl = webUrl,
                DriveItemId = uploaded.Id ?? string.Empty
            };
        }

        private void EnsureSharePointMode()
        {
            var mode = (options.Mode ?? string.Empty).Trim();
            if (!string.Equals(mode, "SharePoint", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("SummaryFileStorage:Mode must be SharePoint for PDF link delivery.");
        }

        private async Task<string> ResolveDriveIdAsync()
        {
            if (!string.IsNullOrWhiteSpace(options.DriveId))
                return options.DriveId.Trim();

            var siteId = await ResolveSiteIdAsync();
            var drives = await graphServiceClient.Sites[siteId].Drives.Request().GetAsync();
            var configuredDriveName = (options.DriveName ?? string.Empty).Trim();
            var drive = drives.CurrentPage.FirstOrDefault(item =>
                    !string.IsNullOrWhiteSpace(configuredDriveName)
                    && string.Equals(item.Name, configuredDriveName, StringComparison.OrdinalIgnoreCase))
                ?? drives.CurrentPage.FirstOrDefault(item =>
                    string.Equals(item.Name, "Documents", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(item.Name, "Shared Documents", StringComparison.OrdinalIgnoreCase))
                ?? drives.CurrentPage.FirstOrDefault();

            if (drive == null || string.IsNullOrWhiteSpace(drive.Id))
                throw new InvalidOperationException("No SharePoint document library was found for SummaryFileStorage.");

            return drive.Id;
        }

        private async Task<string> ResolveSiteIdAsync()
        {
            if (!string.IsNullOrWhiteSpace(options.SiteId))
                return options.SiteId.Trim();

            if (string.IsNullOrWhiteSpace(options.SiteUrl))
                throw new InvalidOperationException("Configure SummaryFileStorage:SiteId or SummaryFileStorage:SiteUrl.");

            var siteUri = new Uri(options.SiteUrl);
            var sitePath = Uri.UnescapeDataString(siteUri.AbsolutePath).TrimEnd('/');
            if (string.IsNullOrWhiteSpace(sitePath))
                sitePath = "/";

            var site = await graphServiceClient.Sites.GetByPath(sitePath, siteUri.Host).Request().GetAsync();
            if (site == null || string.IsNullOrWhiteSpace(site.Id))
                throw new InvalidOperationException($"Unable to resolve SharePoint site from {options.SiteUrl}.");

            return site.Id;
        }

        private async Task EnsureFolderPathAsync(string driveId, string folderPath)
        {
            if (string.IsNullOrWhiteSpace(folderPath))
                return;

            var segments = folderPath
                .Split('/', StringSplitOptions.RemoveEmptyEntries)
                .Select(segment => segment.Trim())
                .Where(segment => segment.Length > 0)
                .ToList();

            var current = await graphServiceClient.Drives[driveId].Root.Request().GetAsync();
            foreach (var segment in segments)
            {
                current = await GetOrCreateChildFolderAsync(driveId, current.Id, segment);
            }
        }

        private async Task<DriveItem> GetOrCreateChildFolderAsync(string driveId, string parentId, string folderName)
        {
            try
            {
                return await graphServiceClient.Drives[driveId]
                    .Items[parentId]
                    .ItemWithPath(folderName)
                    .Request()
                    .GetAsync();
            }
            catch (ServiceException ex) when (ex.StatusCode == System.Net.HttpStatusCode.NotFound)
            {
                var folder = new DriveItem
                {
                    Name = folderName,
                    Folder = new Folder(),
                    AdditionalData = new Dictionary<string, object>
                    {
                        ["@microsoft.graph.conflictBehavior"] = "replace"
                    }
                };

                return await graphServiceClient.Drives[driveId]
                    .Items[parentId]
                    .Children
                    .Request()
                    .AddAsync(folder);
            }
        }

        private async Task<string> CreateOrganizationLinkOrFallbackAsync(string driveId, DriveItem uploaded)
        {
            if (uploaded == null || string.IsNullOrWhiteSpace(uploaded.Id))
                throw new InvalidOperationException("Uploaded SharePoint drive item has no ID.");

            try
            {
                var permission = await graphServiceClient.Drives[driveId]
                    .Items[uploaded.Id]
                    .CreateLink(options.LinkType, options.LinkScope)
                    .Request()
                    .PostAsync();

                if (!string.IsNullOrWhiteSpace(permission?.Link?.WebUrl))
                    return permission.Link.WebUrl;
            }
            catch (ServiceException ex)
            {
                logger.LogWarning(ex,
                    "[SharePointFileStorage] Create sharing link failed, falling back to driveItem.webUrl, driveItemId={DriveItemId}",
                    uploaded.Id);
            }

            if (string.IsNullOrWhiteSpace(uploaded.WebUrl))
                throw new InvalidOperationException("SharePoint upload succeeded but no webUrl was returned.");

            return uploaded.WebUrl;
        }

        private string BuildUniqueFileName(string fileName)
        {
            var fallback = string.IsNullOrWhiteSpace(fileName) ? "会议总结.pdf" : fileName.Trim();
            var extension = Path.GetExtension(fallback);
            if (string.IsNullOrWhiteSpace(extension))
                extension = ".pdf";

            var baseName = Path.GetFileNameWithoutExtension(fallback);
            baseName = InvalidFileNameChars.Replace(baseName, "_");
            baseName = Whitespace.Replace(baseName, " ").Trim();
            if (string.IsNullOrWhiteSpace(baseName))
                baseName = "会议总结";

            var stamp = DateTimeOffset.UtcNow.ToString("yyyyMMdd-HHmmss");
            return $"{baseName}_{stamp}{extension}";
        }

        private static string NormalizePath(string? path)
        {
            if (string.IsNullOrWhiteSpace(path))
                return string.Empty;

            return string.Join('/',
                path.Split(new[] { '/', '\\' }, StringSplitOptions.RemoveEmptyEntries)
                    .Select(part => part.Trim())
                    .Where(part => part.Length > 0));
        }
    }
}
