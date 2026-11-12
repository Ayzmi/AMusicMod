param([int]$IntervalMs = 3000, [string]$PreferredSource = 'auto')
# AMusicMod media reader: talks to the Windows System Media Transport Controls (same thing as the media keys).
# Reads commands from stdin (play_pause, next, prev, active, inactive) and prints STATE lines to stdout.
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less files as ANSI.

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
    $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]

    $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]

    function Await($op, [Type]$type) {
        $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
        $null = $task.Wait(5000)
        if ($task.IsCompleted) { return $task.Result }
        return $null
    }

    $mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
    $propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
    $mgr = Await ($mgrType::RequestAsync()) $mgrType
    if ($null -eq $mgr) { throw 'Could not open the Windows media session manager' }

    function Clean($t) { return (("$t") -replace '[\t\r\n]', ' ') }

    function Session {
        if ($PreferredSource -eq 'auto') { return $mgr.GetCurrentSession() }
        foreach ($candidate in $mgr.GetSessions()) {
            $appId = $candidate.SourceAppUserModelId
            if ($PreferredSource -eq 'spotify' -and $appId -match 'spotify') { return $candidate }
            if ($PreferredSource -eq 'browser' -and $appId -match 'chrome|msedge|firefox|brave|opera') { return $candidate }
        }
        return $null
    }

    $script:coverKey = ''
    $script:cover = ''
    function Cover($props, $key) {
        if ($script:coverKey -eq $key) { return $script:cover }
        $script:coverKey = $key
        $script:cover = ''
        if ($null -eq $props -or $null -eq $props.Thumbnail) { return '' }
        $managed = $null; $memory = $null
        try {
            $stream = Await ($props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime])
            if ($null -ne $stream -and $stream.Size -gt 0 -and $stream.Size -le 524288) {
                $managed = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
                $memory = [System.IO.MemoryStream]::new()
                $managed.CopyTo($memory)
                $script:cover = 'base64:' + [Convert]::ToBase64String($memory.ToArray())
            }
        } catch { } finally {
            if ($null -ne $memory) { $memory.Dispose() }
            if ($null -ne $managed) { $managed.Dispose() }
        }
        return $script:cover
    }

    function Emit {
        $s = Session
        if ($null -eq $s) { [Console]::Out.WriteLine("STATE`tnone"); return }
        $status = [int]$s.GetPlaybackInfo().PlaybackStatus   # 4 = Playing, 5 = Paused
        $props = Await ($s.TryGetMediaPropertiesAsync()) $propsType
        $title = ''; $artist = ''
        if ($null -ne $props) { $title = Clean $props.Title; $artist = Clean $props.Artist }
        $tl = $s.GetTimelineProperties()
        $pos = [int64]$tl.Position.TotalMilliseconds
        $dur = [int64]($tl.EndTime - $tl.StartTime).TotalMilliseconds
        # Position is a snapshot at LastUpdatedTime, not necessarily the position right now.
        # Without this, every poll can rewind the Java clock and repeat a lyric line.
        if ($status -eq 4) {
            $elapsed = ([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds
            if ($elapsed -ge 0 -and $elapsed -lt 86400000) { $pos += [int64]$elapsed }
        }
        if ($dur -gt 0) { $pos = [Math]::Min($pos, $dur) }
        $album = ''; $art = ''
        if ($null -ne $props) {
            $album = Clean $props.AlbumTitle
            $art = Cover $props ($s.SourceAppUserModelId + '|' + $title + '|' + $artist + '|' + $album)
        }
        [Console]::Out.WriteLine("STATE`t$status`t$title`t$artist`t$pos`t$dur`t$album`t$art")
    }

    function Command($cmd) {
        $s = Session
        switch ($cmd) {
            'play_pause' { if ($null -ne $s) { $null = Await ($s.TryTogglePlayPauseAsync()) ([bool]) } }
            'next'       { if ($null -ne $s) { $null = Await ($s.TrySkipNextAsync()) ([bool]) } }
            'prev'       { if ($null -ne $s) { $null = Await ($s.TrySkipPreviousAsync()) ([bool]) } }
            'active'     { $script:active = $true;  $script:last = -1000000 }
            'inactive'   { $script:active = $false }
        }
    }

    $script:active = $false
    $script:last = -1000000
    $reader = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
    $readTask = $reader.ReadLineAsync()
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    [Console]::Out.WriteLine('READY')

    while ($true) {
        if ($readTask.IsCompleted) {
            $line = $readTask.Result
            if ($null -eq $line) { break }   # the game closed stdin -> exit
            $readTask = $reader.ReadLineAsync()
            try {
                Command ($line.Trim())
                if ($script:active) { Start-Sleep -Milliseconds 300; Emit; $script:last = $sw.ElapsedMilliseconds }
            } catch {
                [Console]::Out.WriteLine("ERR`t" + (Clean $_.Exception.Message))
            }
        }
        if ($script:active -and ($sw.ElapsedMilliseconds - $script:last) -ge $IntervalMs) {
            try { Emit } catch { [Console]::Out.WriteLine("ERR`t" + (Clean $_.Exception.Message)) }
            $script:last = $sw.ElapsedMilliseconds
        }
        Start-Sleep -Milliseconds 200
    }
} catch {
    [Console]::Out.WriteLine("ERR`t" + (("$($_.Exception.Message)") -replace '[\t\r\n]', ' '))
    exit 1
}
