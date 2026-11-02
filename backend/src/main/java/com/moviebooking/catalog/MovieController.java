package com.moviebooking.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moviebooking.catalog.CatalogService.MovieResponse;

@RestController
@RequestMapping("/api/movies")
public class MovieController {

    private final CatalogService catalogService;

    public MovieController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<MovieResponse> movies() {
        return catalogService.listMovies();
    }
}
