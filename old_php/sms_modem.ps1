# Talks to the USB dongle over the serial port (PDU mode) and reports
# results back to send_sms.php as one JSON object per line.
# Do not run directly.
param(
    [Parameter(Mandatory = $true)][string]$Port,
    [Parameter(Mandatory = $true)][string]$JobFile,
    [int]$WaitSeconds = 120,
    [int]$Baud = 9600
)

function Emit($obj) {
    [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress))
    [Console]::Out.Flush()
}

# ---------------- PDU helpers ----------------

# GSM characters that need the escape (0x1B) prefix
$script:gsmExt = @{ 94 = 0x14; 123 = 0x28; 125 = 0x29; 92 = 0x2F; 91 = 0x3C; 126 = 0x3D; 93 = 0x3E; 124 = 0x40 }

function Swap-Digits([string]$d) {
    if ($d.Length % 2) { $d += 'F' }
    $o = ''
    for ($i = 0; $i -lt $d.Length; $i += 2) { $o += "$($d[$i + 1])$($d[$i])" }
    return $o
}

function Encode-Pdu([string]$phone, [string]$msg) {
    $num  = $phone.TrimStart('+')
    $type = if ($phone.StartsWith('+')) { '91' } else { '81' }

    # Convert text to GSM 7-bit septets
    $sept = New-Object System.Collections.Generic.List[int]
    foreach ($ch in $msg.ToCharArray()) {
        $c = [int]$ch
        if     ($c -eq 64)                        { $sept.Add(0) }      # @
        elseif ($c -eq 36)                        { $sept.Add(2) }      # $
        elseif ($c -eq 95)                        { $sept.Add(0x11) }   # _
        elseif ($c -eq 96)                        { $sept.Add(39) }     # ` -> '
        elseif ($script:gsmExt.ContainsKey($c))   { $sept.Add(27); $sept.Add($script:gsmExt[$c]) }
        elseif ($c -ge 32 -and $c -le 126)        { $sept.Add($c) }
        elseif ($c -eq 10 -or $c -eq 13)          { $sept.Add($c) }
        else                                      { $sept.Add(63) }     # ?
    }
    while ($sept.Count -gt 160) { $sept.RemoveAt($sept.Count - 1) }
    if ($sept.Count -gt 0 -and $sept[$sept.Count - 1] -eq 27) { $sept.RemoveAt($sept.Count - 1) }

    # Pack septets into octets
    $bytes = New-Object System.Collections.Generic.List[byte]
    [long]$bits = 0; $nbits = 0
    foreach ($s in $sept) {
        $bits = $bits -bor ([long]$s -shl $nbits)
        $nbits += 7
        while ($nbits -ge 8) {
            $bytes.Add([byte]($bits -band 0xFF))
            $bits = $bits -shr 8
            $nbits -= 8
        }
    }
    if ($nbits -gt 0) { $bytes.Add([byte]($bits -band 0xFF)) }
    $ud = ($bytes | ForEach-Object { $_.ToString('X2') }) -join ''

    # 31 = SMS-SUBMIT + validity period + request delivery report
    $tpdu = '31' + '00' + $num.Length.ToString('X2') + $type + (Swap-Digits $num) +
            '00' + '00' + 'A7' + $sept.Count.ToString('X2') + $ud

    return @{ pdu = '00' + $tpdu; len = $tpdu.Length / 2 }
}

# Returns @(messageRef, status) from a status-report PDU, or $null
function Parse-StatusReport([int]$len, [string]$hex) {
    try {
        $b = for ($i = 0; $i -lt $hex.Length; $i += 2) { [Convert]::ToByte($hex.Substring($i, 2), 16) }
        $p = 0
        if ($b.Count -ne $len) { $p = 1 + $b[0] }   # skip SMSC header if present
        $p++                                         # first octet
        $mr = [int]$b[$p]; $p++
        $raLen = [int]$b[$p]
        $p += 2 + [math]::Ceiling($raLen / 2)        # recipient address
        $p += 14                                     # two timestamps
        return @($mr, [int]$b[$p])
    } catch { return $null }
}

# ---------------- Serial port ----------------

$jobs = (Get-Content -Raw -Encoding UTF8 $JobFile) | ConvertFrom-Json

$sp = New-Object System.IO.Ports.SerialPort $Port, $Baud, 'None', 8, 'One'
$sp.Handshake    = 'None'
$sp.DtrEnable    = $true
$sp.RtsEnable    = $true
$sp.ReadTimeout  = 500
$sp.WriteTimeout = 5000

try { $sp.Open() }
catch {
    Emit @{ type = 'fatal'; text = "Cannot open $Port - close Mobile Partner / check COM number" }
    exit 1
}

$script:buf    = ''
$script:refMap = @{}   # modem message reference -> line index
$script:done   = @{}   # line index -> final delivery status received

# Read modem output and process any delivery reports (+CDS)
function Pump {
    try { $data = $sp.ReadExisting() } catch { $data = '' }
    if ($data) { $script:buf += $data }

    while ($true) {
        $m = [regex]::Match($script:buf, '\+CDS:\s*(\d+)\s*\r?\n\s*([0-9A-Fa-f]+)\r?\n')
        if (-not $m.Success) { break }
        $script:buf = $script:buf.Remove($m.Index, $m.Length)

        $r = Parse-StatusReport ([int]$m.Groups[1].Value) $m.Groups[2].Value
        if (-not $r) { continue }
        $mr = $r[0]; $st = $r[1]
        if (-not $script:refMap.ContainsKey($mr)) { continue }

        $idx = $script:refMap[$mr]
        if ($st -lt 32)     { $status = 'DELIVERED' }
        elseif ($st -lt 64) { continue }                  # network still trying
        else                { $status = "NOT DELIVERED (code $st)" }
        $script:done[$idx] = $true
        Emit @{ type = 'delivery'; index = $idx; status = $status }
    }
}

function Wait-For([string]$pattern, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        Pump
        $m = [regex]::Match($script:buf, $pattern)
        if ($m.Success) {
            $script:buf = $script:buf.Substring($m.Index + $m.Length)
            return $m
        }
        Start-Sleep -Milliseconds 100
    }
    return $null
}

function Send-Cmd([string]$cmd, [int]$timeoutSec = 5) {
    Pump
    $script:buf = ''
    $sp.Write("$cmd`r")
    $r = Wait-For '(OK|\+CM[SE] ERROR:[^\r\n]*|ERROR)\r' $timeoutSec
    return ($r -and $r.Groups[1].Value -eq 'OK')
}

# ---------------- Modem setup ----------------
if (-not (Send-Cmd 'AT')) {
    Emit @{ type = 'fatal'; text = "Modem not responding on $Port - try the other dongle COM port" }
    $sp.Close(); exit 1
}
Send-Cmd 'ATE0' | Out-Null
if (-not (Send-Cmd 'AT+CMGF=0')) {
    Emit @{ type = 'fatal'; text = 'Modem rejected PDU mode (AT+CMGF=0)' }
    $sp.Close(); exit 1
}
$reports = Send-Cmd 'AT+CNMI=2,1,0,1,0'
if ($reports) { Emit @{ type = 'log'; text = 'Modem ready - PDU mode (delivery reports ON).' } }
else          { Emit @{ type = 'log'; text = 'Modem ready - PDU mode (this port does not support delivery reports).' } }

# ---------------- Send messages ----------------
foreach ($job in $jobs) {
    $enc = Encode-Pdu $job.phone $job.message

    Pump
    $script:buf = ''
    $sp.Write("AT+CMGS=$($enc.len)`r")

    if (-not (Wait-For '>' 10)) {
        $sp.Write([string][char]27)
        Emit @{ type = 'sent'; index = $job.index; ok = $false; error = 'No prompt from modem' }
        continue
    }

    $sp.Write($enc.pdu + [char]26)
    $r = Wait-For '\+CMGS:\s*(\d+)|\+CM[SE] ERROR:\s*[^\r\n]*|ERROR' 60

    if (-not $r) {
        Emit @{ type = 'sent'; index = $job.index; ok = $false; error = 'Timeout (no reply from network)' }
    }
    elseif ($r.Groups[1].Success) {
        $script:refMap[[int]$r.Groups[1].Value] = $job.index
        Emit @{ type = 'sent'; index = $job.index; ok = $true }
    }
    else {
        Emit @{ type = 'sent'; index = $job.index; ok = $false; error = $r.Value.Trim() }
    }
    Start-Sleep -Seconds 2
}

# ---------------- Wait for delivery reports ----------------
if ($reports -and $script:refMap.Count -gt 0) {
    Emit @{ type = 'log'; text = "Waiting up to $WaitSeconds seconds for delivery reports..." }
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $deadline -and $script:done.Count -lt $script:refMap.Count) {
        Pump
        Start-Sleep -Milliseconds 500
    }
}

foreach ($idx in $script:refMap.Values) {
    if (-not $script:done.ContainsKey($idx)) {
        Emit @{ type = 'delivery'; index = $idx; status = 'NO REPORT' }
    }
}

$sp.Close()
