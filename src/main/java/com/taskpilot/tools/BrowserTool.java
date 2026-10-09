package com.taskpilot.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.taskpilot.domain.SideEffect;
import com.taskpilot.domain.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * BrowserTool: web browser automation via Playwright.
 * Operations: open, snapshot, click, type, select, upload, screenshot
 *
 * The agent navigates by element refs (stable IDs from snapshot), not hardcoded selectors.
 * SideEffect: REVERSIBLE for navigation/typing, EXTERNAL for submit/form submission clicks.
 */
@Component
public class BrowserTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BrowserTool.class);

    private Playwright playwright;
    private Browser browser;
    private Page page;

    @Override public String name() { return "browser"; }

    @Override public String description() {
        return "Control a web browser. Operations: open (url), snapshot (get page state with element refs), " +
               "click (ref), type (ref, text), select (ref, value), upload (ref, filePath), screenshot. " +
               "Always snapshot before clicking to get current element refs.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "op", Map.of("type", "string", "enum", List.of("open","snapshot","click","type","select","upload","screenshot")),
                "url", Map.of("type", "string", "description", "URL to navigate to"),
                "ref", Map.of("type", "string", "description", "Element ref from snapshot"),
                "text", Map.of("type", "string", "description", "Text to type"),
                "value", Map.of("type", "string", "description", "Value to select"),
                "filePath", Map.of("type", "string", "description", "Path to file to upload")
            ),
            "required", List.of("op")
        );
    }

    @Override public SideEffect sideEffect() { return SideEffect.REVERSIBLE; }

    @Override public SideEffect sideEffectFor(Map<String, Object> args) {
        // Clicking a submit button is EXTERNAL — PolicyGate will check the snapshot context
        String op = (String) args.getOrDefault("op", "");
        String ref = (String) args.getOrDefault("ref", "");
        if ("click".equals(op) && ref.contains("submit")) {
            return SideEffect.EXTERNAL;
        }
        return SideEffect.REVERSIBLE;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String op = (String) input.getOrDefault("op", "snapshot");
        try {
            ensureBrowserOpen();
            return switch (op) {
                case "open"       -> openUrl((String) input.get("url"));
                case "snapshot"   -> snapshot();
                case "click"      -> click((String) input.get("ref"));
                case "type"       -> typeText((String) input.get("ref"), (String) input.get("text"));
                case "select"     -> selectOption((String) input.get("ref"), (String) input.get("value"));
                case "upload"     -> uploadFile((String) input.get("ref"), (String) input.get("filePath"));
                case "screenshot" -> screenshot();
                default           -> ToolResult.failure("INVALID_OP", "Unknown browser op: " + op);
            };
        } catch (Exception e) {
            log.error("BrowserTool error: {}", e.getMessage(), e);
            return ToolResult.failure("BROWSER_ERROR", e.getMessage());
        }
    }

    private void ensureBrowserOpen() {
        if (playwright == null) {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        }
        if (page == null || page.isClosed()) {
            page = browser.newPage();
        }
    }

    private ToolResult openUrl(String url) {
        if (url == null || url.isBlank()) return ToolResult.failure("INVALID_ARGS", "url is required");
        log.info("Browser: opening {}", url);
        page.navigate(url, new Page.NavigateOptions().setTimeout(6000));
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(3000));
        } catch (Exception ignored) {}
        return snapshot();
    }

    private ToolResult snapshot() {
        String url = page.url();
        String title = page.title();

        // Get visible text
        String bodyText = page.evaluate("() => document.body ? document.body.innerText : ''").toString();
        if (bodyText.length() > 3000) bodyText = bodyText.substring(0, 3000) + "...[truncated]";

        // Get interactive elements with stable refs
        List<Map<String, Object>> elements = new ArrayList<>();
        @SuppressWarnings("unchecked")
        var rawElements = (List<Map<String, Object>>) page.evaluate("""
            () => {
                const els = [];
                const selectors = ['input', 'textarea', 'select', 'button', '[role=button]', 'a[href]'];
                let idx = 0;
                selectors.forEach(sel => {
                    document.querySelectorAll(sel).forEach(el => {
                        if (el.offsetParent === null) return; // skip hidden
                        const label = el.getAttribute('aria-label')
                            || el.getAttribute('placeholder')
                            || (el.labels && el.labels[0] ? el.labels[0].innerText : '')
                            || el.name || el.id || el.type || el.tagName;
                        const ref = (el.id && el.id !== '') ? '#' + el.id
                                  : (el.name ? '[name=' + el.name + ']' : sel + '-' + idx);
                        idx++;
                        const item = {
                            ref: ref,
                            tag: el.tagName.toLowerCase(),
                            type: el.type || '',
                            label: label ? label.trim().substring(0, 60) : '',
                            required: el.required || false,
                            value: (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') ? el.value : ''
                        };
                        if (el.tagName === 'SELECT') {
                            item.options = Array.from(el.options).map(o => o.value);
                        }
                        els.push(item);
                    });
                });
                return els;
            }
        """);
        if (rawElements != null) elements.addAll(rawElements);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("title", title);
        data.put("text", bodyText);
        data.put("elements", elements);
        data.put("elementCount", elements.size());
        return ToolResult.success(data);
    }

    private ToolResult click(String ref) {
        if (ref == null) return ToolResult.failure("INVALID_ARGS", "ref is required for click");
        log.info("Browser: clicking {}", ref);
        page.locator(ref).first().click(new Locator.ClickOptions().setTimeout(10000));
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(5000));
        return snapshot();
    }

    private ToolResult typeText(String ref, String text) {
        if (ref == null || text == null) return ToolResult.failure("INVALID_ARGS", "ref and text required");
        log.info("Browser: typing into {}", ref);
        Locator locator = page.locator(ref).first();
        locator.clear();
        locator.fill(text);
        return ToolResult.success(Map.of("typed", true, "ref", ref, "length", text.length()));
    }

    private ToolResult selectOption(String ref, String value) {
        if (ref == null || value == null) return ToolResult.failure("INVALID_ARGS", "ref and value required");
        log.info("Browser: selecting {} in {}", value, ref);
        page.locator(ref).first().selectOption(value);
        return ToolResult.success(Map.of("selected", true, "ref", ref, "value", value));
    }

    private ToolResult uploadFile(String ref, String filePath) {
        if (ref == null || filePath == null) return ToolResult.failure("INVALID_ARGS", "ref and filePath required");
        Path file = Path.of(filePath);
        if (!Files.exists(file)) {
            return ToolResult.failure("FILE_NOT_FOUND", "File not found: " + filePath);
        }
        log.info("Browser: uploading {} to {}", filePath, ref);
        page.locator(ref).first().setInputFiles(file);
        return ToolResult.success(Map.of("uploaded", true, "file", filePath));
    }

    private ToolResult screenshot() {
        try {
            Path screenshotDir = Path.of("evidence/screenshots");
            Files.createDirectories(screenshotDir);
            String filename = "screenshot-" + System.currentTimeMillis() + ".png";
            Path screenshotPath = screenshotDir.resolve(filename);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));
            return ToolResult.success(
                Map.of("screenshot", screenshotPath.toString()),
                List.of(screenshotPath.toString())
            );
        } catch (Exception e) {
            return ToolResult.failure("SCREENSHOT_ERROR", e.getMessage());
        }
    }
}
