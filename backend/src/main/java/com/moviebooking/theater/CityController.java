package com.moviebooking.theater;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moviebooking.theater.TheaterService.CityResponse;

@RestController
@RequestMapping("/api/cities")
public class CityController {

    private final TheaterService theaterService;

    public CityController(TheaterService theaterService) {
        this.theaterService = theaterService;
    }

    @GetMapping
    public List<CityResponse> cities() {
        return theaterService.listCities();
    }
}
