# Build four short excerpts that reproduce two REAL track transitions:
#   the genuine last 35s of one track, then the genuine first 35s of the next.
# Anything else would be judging a crossfade against a splice.
#
# Push the results to the emulator with:
#   adb -s <device> push <out>\*.flac /sdcard/Music/CrossfadeTest/
# then grant that folder in the app via Add folder.
$out = Join-Path $env:TEMP "atomicamp-xfade"
$status = Join-Path $out "excerpt-status.txt"
New-Item -ItemType Directory -Force -Path $out | Out-Null
Get-ChildItem $out -Filter *.flac | Remove-Item -Force

$M = "K:\Media\Music"
$jobs = @(
  @{ n=1; src="$M\Willie Nelson - Shotgun Willie (1973 Pop) [Flac 24-192]\01. Willie Nelson - Shotgun Willie.flac";      mode="end";   title="01 Willie - Shotgun Willie (real ending)";  fmt="s32" }
  @{ n=2; src="$M\Willie Nelson - Shotgun Willie (1973 Pop) [Flac 24-192]\02. Willie Nelson - Whiskey River.flac";       mode="start"; title="02 Willie - Whiskey River (real start)";   fmt="s32" }
  @{ n=3; src="$M\NOFX - Ribbed [FLAC]\01 Green Corn.flac";                                                              mode="end";   title="03 NOFX - Green Corn (real ending)";    fmt="s16" }
  @{ n=4; src="$M\NOFX - Ribbed [FLAC]\02 The Moron Brothers.flac";                                                      mode="start"; title="04 NOFX - Moron Brothers (real start)";  fmt="s16" }
)

$total = $jobs.Count
$i = 0
foreach ($j in $jobs) {
    $i++
    $pct = [int](100 * ($i - 1) / $total)
    $filled = [int]($pct * 40 / 100)
    $bar = ('#' * $filled) + ('.' * (40 - $filled))
    @("EXCERPTS  [$bar] $pct%", "  ($i/$total) $($j.title)") | Set-Content $status -Encoding ascii

    $dest = Join-Path $out ("{0:d2}.flac" -f $j.n)
    $common = @(
        "-loglevel","error","-y",
        "-c:a","flac","-sample_fmt",$j.fmt,
        "-map_metadata","-1",
        "-metadata","title=$($j.title)",
        "-metadata","artist=AtomicAmp",
        "-metadata","album=Crossfade Test",
        "-metadata","track=$($j.n)"
    )
    if ($j.mode -eq "end") {
        & ffmpeg -sseof -35 -i $j.src @common $dest
    } else {
        & ffmpeg -i $j.src -t 35 @common $dest
    }
}

@("EXCERPTS  [########################################] 100%", "  done") | Set-Content $status -Encoding ascii
Get-ChildItem $out -Filter *.flac | Sort-Object Name | ForEach-Object {
    $d = & ffprobe -v error -show_entries "format=duration" -of "default=noprint_wrappers=1:nokey=1" $_.FullName
    $sr = & ffprobe -v error -select_streams a:0 -show_entries "stream=sample_rate,bits_per_raw_sample" -of "default=noprint_wrappers=1:nokey=1" $_.FullName
    "{0}  {1} MB  {2}s  [{3}]" -f $_.Name, [math]::Round($_.Length/1MB,1), [math]::Round([double]$d,1), ($sr -join '/')
}
