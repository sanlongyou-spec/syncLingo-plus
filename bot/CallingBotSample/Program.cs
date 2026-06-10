// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

using System;
using System.IO;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace CallingBotSample
{
    public class Program
    {
        public static void Main(string[] args)
        {
            CreateHostBuilder(args).Build().Run();
        }

        public static IHostBuilder CreateHostBuilder(string[] args) =>
            Host.CreateDefaultBuilder(args)
                .ConfigureLogging(logging =>
                {
                    var logPath = Path.Combine(Path.GetTempPath(), "bot.log");
                    logging.AddProvider(new FileLoggerProvider(logPath));
                })
                .ConfigureWebHostDefaults(webBuilder =>
                {
                    webBuilder.UseStartup<Startup>();
                });
    }

    // Minimal file logger — writes every log line to %TEMP%\bot.log
    public sealed class FileLoggerProvider : ILoggerProvider
    {
        private readonly StreamWriter writer;
        private readonly object _lock = new();

        public FileLoggerProvider(string path)
        {
            writer = new StreamWriter(path, append: false) { AutoFlush = true };
        }

        public ILogger CreateLogger(string categoryName) => new FileLogger(categoryName, writer, _lock);

        public void Dispose() => writer.Dispose();
    }

    public sealed class FileLogger : ILogger
    {
        private readonly string category;
        private readonly StreamWriter writer;
        private readonly object _lock;

        public FileLogger(string category, StreamWriter writer, object lockObj)
        {
            this.category = category;
            this.writer = writer;
            this._lock = lockObj;
        }

        public IDisposable BeginScope<TState>(TState state) => NullScope.Instance;
        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Information;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel)) return;
            var line = $"{DateTime.Now:HH:mm:ss} [{logLevel}] {category}: {formatter(state, exception)}";
            if (exception != null) line += $"\n{exception}";
            lock (_lock) writer.WriteLine(line);
        }

        private sealed class NullScope : IDisposable
        {
            public static NullScope Instance { get; } = new();
            public void Dispose() { }
        }
    }
}
