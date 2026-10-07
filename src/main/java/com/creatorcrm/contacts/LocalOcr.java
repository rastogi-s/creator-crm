package com.creatorcrm.contacts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads text in pictures with what the computer already has, so it costs nothing: on Windows 10 and 11 the text
 * reader built into Windows (the one the Photos and Snipping Tool apps use), elsewhere Tesseract if it is installed.
 * Nothing leaves the computer.
 */
@Component
public class LocalOcr implements ImageOcr {
    private static final Logger log = LoggerFactory.getLogger(LocalOcr.class);
    static final long TIMEOUT_SECONDS = 60;

    private final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private volatile String brokenBecause;

    @Override
    public String unavailable() {
        if (brokenBecause != null) return brokenBecause;
        if (windows || tesseract() != null) return null;
        return "This computer can't read text in pictures by itself.";
    }

    @Override
    public String read(byte[] image, String ext) throws IOException {
        String why = unavailable();
        if (why != null) throw new IOException(why);
        Path dir = Files.createTempDirectory("crm-ocr");
        Path file = dir.resolve("picture." + ext.replaceAll("[^a-z0-9]", ""));
        Path err = dir.resolve("err.txt");
        try {
            Files.write(file, image);
            List<String> cmd = new ArrayList<>();
            if (windows) {
                cmd.addAll(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded(windowsScript(file))));
            } else {
                cmd.addAll(List.of(tesseract(), file.toString(), "stdout"));
            }
            Process p = new ProcessBuilder(cmd).redirectError(err.toFile()).redirectInput(ProcessBuilder.Redirect.from(nullFile())).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Reading the picture took too long");
            }
            String error = Files.exists(err) ? Files.readString(err, StandardCharsets.UTF_8).strip() : "";
            if (p.exitValue() != 0) {
                log.warn("Picture reader failed ({}): {}", p.exitValue(), error);
                if (error.contains("NO_OCR_LANGUAGE")) {
                    brokenBecause = "Windows has no text-reading language installed. In Windows Settings, Time & language, "
                            + "Language, add English with its Optical character recognition option.";
                    throw new IOException(brokenBecause);
                }
                throw new IOException("This computer couldn't read that picture");
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Reading the picture was interrupted", e);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(err);
            Files.deleteIfExists(dir);
        }
    }

    private static File nullFile() {
        return new File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null");
    }

    /** -EncodedCommand takes UTF-16LE base64; it isn't blocked by the script execution policy like a .ps1 file is. */
    static String encoded(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    /** Windows.Media.Ocr from Windows PowerShell 5.1 (built into Windows 10 and 11), printing one line per text line. */
    static String windowsScript(Path image) {
        String path = image.toAbsolutePath().toString().replace("'", "''");
        return String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
                "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
                "$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]",
                "$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]",
                "$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType = WindowsRuntime]",
                "$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and "
                        + "$_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
                "function Await($op, [Type]$type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); "
                        + "$t.Wait(-1) | Out-Null; $t.Result }",
                "$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()",
                "if ($engine -eq $null) { [Console]::Error.WriteLine('NO_OCR_LANGUAGE'); exit 3 }",
                "$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync('" + path + "')) ([Windows.Storage.StorageFile])",
                "$stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])",
                "$decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])",
                "$bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])",
                "$result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])",
                "foreach ($line in $result.Lines) { [Console]::Out.WriteLine($line.Text) }",
                "$stream.Dispose()");
    }

    /** Tesseract on PATH, or in its usual install folders. Null when it isn't installed. */
    private static String tesseract() {
        List<String> candidates = new ArrayList<>();
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isBlank()) {
                candidates.add(dir + File.separator + "tesseract");
                candidates.add(dir + File.separator + "tesseract.exe");
            }
        }
        candidates.addAll(List.of("/opt/homebrew/bin/tesseract", "/usr/local/bin/tesseract",
                "C:\\Program Files\\Tesseract-OCR\\tesseract.exe"));
        return candidates.stream().filter(c -> new File(c).canExecute()).findFirst().orElse(null);
    }
}
