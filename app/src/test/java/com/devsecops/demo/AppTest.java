package com.devsecops.demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AppTest {

    @Test
    void testHomeHtmlIsNotNull() {
        String html = App.homeHtml();
        assertNotNull(html);
    }

    @Test
    void testHomeHtmlIsNotEmpty() {
        String html = App.homeHtml();
        assertTrue(html.length() > 0);
    }

    @Test
    void testHomeHtmlContainsExpectedContent() {
        String html = App.homeHtml();
        assertTrue(html.contains("DevSecOps CI/CD Demo"));
    }
}
