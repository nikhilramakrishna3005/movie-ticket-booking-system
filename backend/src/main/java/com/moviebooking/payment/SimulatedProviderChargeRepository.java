package com.moviebooking.payment;

import org.springframework.data.jpa.repository.JpaRepository;

interface SimulatedProviderChargeRepository extends JpaRepository<SimulatedProviderCharge, String> {
}
