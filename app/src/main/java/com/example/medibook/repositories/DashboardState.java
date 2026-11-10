package com.example.medibook.repositories;

/** LIVE means an RTDB read model agrees with a current server Firestore result, not a backend SLA. */
public enum DashboardState { LOADING, LIVE, FIRESTORE, CACHED, ERROR }
