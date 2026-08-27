package com.flatio.domain.blacklist;

/**
 * Kind of item a {@link BlacklistEntry} excludes from a user's search results.
 *
 * <p>LISTING entries store a listing's numeric ID; SOURCE entries store a source's {@code code}
 * (its numeric ID is never exposed to clients); KEYWORD entries store a free-text stop-word.
 * See {@link BlacklistEntry#getValue()}.
 */
public enum BlacklistEntryType {
  LISTING,
  SOURCE,
  KEYWORD
}
