<?php
/*
 * Local SMS sender (USB dongle, Windows + XAMPP PHP)
 *
 * Reads a TXT file where each line is:   PHONE <TAB> MESSAGE
 * Sends every message through the dongle and writes the result back
 * to the end of each line (Sri Lanka time):
 *
 *   PHONE  MESSAGE  SEND STATUS  SEND TIME  DELIVERY STATUS  DELIVERY TIME
 *
 * Lines already marked SENT are skipped, so you can run it again safely
 * and only new or FAILED lines will be (re)sent.
 */

// ================= Settings =================
const MODEM_PORT   = 'COM10';          // dongle port from Device Manager
const WAIT_REPORTS = 5;             // seconds to wait for delivery reports
const DEFAULT_FILE = 'sms_list.txt';  // used when no file is given
// ============================================

date_default_timezone_set('Asia/Colombo');   // Sri Lanka time (UTC+5:30)

function now_lk() { return date('Y-m-d H:i:s'); }
function say($text) { echo '[' . now_lk() . "] $text\n"; }

function to_intl($phone) {
    $phone = preg_replace('/[^0-9+]/', '', $phone);
    if (preg_match('/^0(\d{9})$/', $phone, $m)) return '+94' . $m[1];
    if (preg_match('/^94\d{9}$/', $phone))       return '+' . $phone;
    return $phone;
}

function save_rows($file, $rows) {
    $out = [];
    foreach ($rows as $r) {
        $out[] = rtrim(implode("\t", $r), "\t");
    }
    if (file_put_contents($file, implode("\r\n", $out) . "\r\n", LOCK_EX) === false) {
        say("WARNING: could not write to $file (is it open in Excel?)");
    }
}

// ---------- Read the input file ----------
$file = $argv[1] ?? (__DIR__ . DIRECTORY_SEPARATOR . DEFAULT_FILE);
if (!is_file($file)) {
    say("File not found: $file");
    exit(1);
}

$raw   = preg_replace('/^\xEF\xBB\xBF/', '', file_get_contents($file)); // strip BOM
$rows  = [];
foreach (preg_split('/\r\n|\r|\n/', $raw) as $line) {
    if (trim($line) === '') continue;
    $cols = strpos($line, "\t") !== false
        ? explode("\t", $line)
        : preg_split('/\s+/', trim($line), 2);      // fallback: first space
    $rows[] = array_pad(array_map('trim', $cols), 6, '');
}

// Backup of the original file before we change anything
$backup = $file . '.' . date('Ymd_His') . '.bak';
copy($file, $backup);
say("Backup saved: $backup");

// ---------- Build the job list ----------
$jobs = [];
foreach ($rows as $i => &$r) {
    if (strtoupper($r[2]) === 'SENT') continue;           // already done

    $phone = to_intl($r[0]);
    $msg   = mb_substr($r[1], 0, 160);

    if (!preg_match('/^\+\d{9,15}$/', $phone) || $msg === '') {
        $r[2] = 'FAILED - invalid number or empty message';
        $r[3] = now_lk();
        $r[4] = $r[5] = '';
        continue;
    }
    $r[2] = 'QUEUED';
    $r[3] = $r[4] = $r[5] = '';
    $jobs[] = ['index' => $i, 'phone' => $phone, 'message' => $msg];
}
unset($r);
save_rows($file, $rows);

if (!$jobs) {
    say('Nothing to send. All lines are already SENT or invalid.');
    exit(0);
}
say(count($jobs) . ' message(s) to send.');

// ---------- Run the modem helper ----------
$jobFile = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'sms_jobs.json';
file_put_contents($jobFile, json_encode($jobs));

$cmd = 'powershell -NoProfile -ExecutionPolicy Bypass -File '
     . escapeshellarg(__DIR__ . DIRECTORY_SEPARATOR . 'sms_modem.ps1')
     . ' -Port ' . MODEM_PORT
     . ' -JobFile ' . escapeshellarg($jobFile)
     . ' -WaitSeconds ' . WAIT_REPORTS;

$proc = proc_open($cmd, [
    1 => ['pipe', 'w'],
    2 => ['file', __DIR__ . DIRECTORY_SEPARATOR . 'modem_errors.log', 'a'],
], $pipes);

if (!is_resource($proc)) {
    say('Could not start PowerShell.');
    exit(1);
}

while (($line = fgets($pipes[1])) !== false) {
    $ev = json_decode(trim($line), true);
    if (!$ev) continue;

    switch ($ev['type']) {
        case 'log':
            say($ev['text']);
            break;

        case 'fatal':
            say('ERROR: ' . $ev['text']);
            foreach ($rows as &$r) {
                if ($r[2] === 'QUEUED') {
                    $r[2] = 'FAILED - ' . $ev['text'];
                    $r[3] = now_lk();
                }
            }
            unset($r);
            break;

        case 'sent':
            $i = (int)$ev['index'];
            if ($ev['ok']) {
                $rows[$i][2] = 'SENT';
                $rows[$i][3] = now_lk();
                $rows[$i][4] = 'WAITING REPORT';
                say("SENT      {$rows[$i][0]}  {$rows[$i][1]}");
            } else {
                $rows[$i][2] = 'FAILED - ' . $ev['error'];
                $rows[$i][3] = now_lk();
                say("FAILED    {$rows[$i][0]}  {$rows[$i][1]}  ({$ev['error']})");
            }
            break;

        case 'delivery':
            $i = (int)$ev['index'];
            $rows[$i][4] = $ev['status'];
            $rows[$i][5] = $ev['status'] === 'NO REPORT' ? '' : now_lk();
            say(str_pad($ev['status'], 10) . "{$rows[$i][0]}  {$rows[$i][1]}");
            break;
    }
    save_rows($file, $rows);   // update the TXT after every event
}

fclose($pipes[1]);
proc_close($proc);
@unlink($jobFile);

// Anything still QUEUED means the helper stopped unexpectedly
foreach ($rows as &$r) {
    if ($r[2] === 'QUEUED') {
        $r[2] = 'FAILED - not sent (see modem_errors.log)';
        $r[3] = now_lk();
    }
}
unset($r);
save_rows($file, $rows);

// ---------- Summary ----------
$sent = $failed = $delivered = 0;
foreach ($rows as $r) {
    if ($r[2] === 'SENT') $sent++;
    if (strpos($r[2], 'FAILED') === 0) $failed++;
    if ($r[4] === 'DELIVERED') $delivered++;
}
say("Done. Sent: $sent | Delivered: $delivered | Failed: $failed");
say("Results written to: $file");
