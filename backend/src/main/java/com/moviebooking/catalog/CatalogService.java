package com.moviebooking.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.config.ResourceNotFoundException;

/** Read access to movies for other packages. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final MovieRepository movies;

    public CatalogService(MovieRepository movies) {
        this.movies = movies;
    }

    public Movie getMovie(long movieId) {
        return movies.findById(movieId).orElseThrow(ResourceNotFoundException::new);
    }
}
