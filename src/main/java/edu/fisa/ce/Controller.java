package edu.fisa.ce;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {
	
	@GetMapping("ce1")
	public String getDate() {
		System.out.println("요청 및 응답(수정 후111) -----------");
		return "응답 데이터";
	}

}
