package com.flatio.service;

/**
 * Summary result of a batch ingestion run.
 *
 * @param added   number of new listings created
 * @param updated number of existing listings updated
 * @param errors  number of listings that failed to ingest
 */
public record BatchIngestResult(int added, int updated, int errors) {}
