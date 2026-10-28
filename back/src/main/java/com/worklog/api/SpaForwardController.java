package com.worklog.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** React Router 경로를 새로고침해도 index.html 이 뜨게 한다 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/work/{id}", "/records", "/report", "/ai", "/settings", "/calendar", "/sessions", "/sessions/{key}", "/reports", "/observer"})
    public String index() {
        return "forward:/index.html";
    }
}
