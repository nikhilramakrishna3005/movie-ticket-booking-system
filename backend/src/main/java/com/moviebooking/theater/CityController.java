package com.moviebooking.theater;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public; anonymous access is granted in SecurityConfig. */
@RestController
@RequestMapping("/api")
public class CityController {

    private final TheaterService theaterService;

    public CityController(TheaterService theaterService) {
        this.theaterService = theaterService;
    }

    @GetMapping("/cities")
    public List<CityView> cities() {
        return theaterService.listCities();
    }
}
