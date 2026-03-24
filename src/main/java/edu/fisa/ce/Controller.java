package edu.fisa.ce;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {
	
	@GetMapping("ce1")
	public String getDate() {
		System.out.println("수정 후!");
		return "응답 데이터 수정 후!! 자동 배포";
	}

}
