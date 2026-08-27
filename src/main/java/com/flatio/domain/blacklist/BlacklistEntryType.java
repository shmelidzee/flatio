package com.flatio.domain.blacklist;

/**
 * Kind of item a {@link BlacklistEntry} excludes from a user's search results.
 *
 * <p>LISTING and SOURCE entries store a numeric ID (of a listing or a source respectively) in
 * {@link BlacklistEntry#getValue()}; KEYWORD entries store a free-text stop-word.
 */
public enum BlacklistEntryType {
  LISTING,
  SOURCE,
  KEYWORD
}
