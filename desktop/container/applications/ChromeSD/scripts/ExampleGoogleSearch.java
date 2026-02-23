import echelon.ui.snap.ChromeScript;
import echelon.ui.snap.ScriptContext;

/**
 * ExampleGoogleSearch — Sample ChromeSD automation script.
 *
 * Demonstrates how to control the currently active JCEF browser tab:
 *   1. Navigate to Google
 *   2. Type a search query via JavaScript
 *   3. Submit the search
 *   4. Wait for results
 *
 * Place this file in:
 *   .../container/applications/ChromeSD/scripts/ExampleGoogleSearch.java
 *
 * Then click the ▶ button next to it in the Chrome Controls side panel.
 * The script runs against whatever tab is currently active — no new window is opened.
 */
public class ExampleGoogleSearch implements ChromeScript {

    @Override
    public void run(ScriptContext ctx) throws Exception {
        ctx.log("Starting Google search script...");

        // Navigate the current tab to Google
        ctx.navigate("https://www.google.com");
        ctx.sleep(2000); // wait for page load

        ctx.log("Page loaded: " + ctx.getURL());

        // Type into the search box and submit via JavaScript
        ctx.executeJS(
            "(() => {"
          + "  const input = document.querySelector('input[name=q], textarea[name=q]');"
          + "  if (input) {"
          + "    input.value = 'Echelon Desktop Java Swing';"
          + "    input.dispatchEvent(new Event('input', { bubbles: true }));"
          + "    input.form.submit();"
          + "  } else {"
          + "    console.error('Search input not found');"
          + "  }"
          + "})()"
        );

        ctx.log("Search submitted, waiting for results...");
        ctx.sleep(3000); // wait for results to load

        // Highlight all result links with a colored border (visual feedback)
        ctx.executeJS(
            "document.querySelectorAll('h3').forEach(h => {"
          + "  h.style.border = '2px solid #00BCD4';"
          + "  h.style.borderRadius = '4px';"
          + "  h.style.padding = '2px 4px';"
          + "})"
        );

        ctx.log("Results highlighted! Current URL: " + ctx.getURL());
        ctx.log("Script complete.");
    }
}