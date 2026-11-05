# ADR-029: Exact owner-managed alias phrases in memory search

**Status:** Implemented and host-verified; owner/device acceptance pending.  
**Date:** 2026-11-04

## Context

`search_memory` returned a PERSON hit for an owner-managed alias only when the complete query exactly equaled the alias. Natural questions that included the same name with surrounding words or punctuation therefore missed that explicit identity evidence.

## Decision

Search active aliases in the current scope for a whole contiguous sequence of Unicode letter/number tokens in the query. Case and whitespace are normalized through the same NFC/lowercase alias normalization; punctuation acts as a token boundary. Return the existing canonical person ID/name, alias revision and source turn. Retired aliases, aliases from another scope, and aliases that now collide with an active canonical person name in the same scope are excluded. This is exact phrase matching; it does not do fuzzy matching, infer relationships, or mutate records.

## Limits

This adds identity evidence to `search_memory`; it does not establish a task/case/finance relationship or semantic relevance. Any domain relationship still requires its existing explicit confirmation path. A synthetic host test covers active match, punctuation, retired/foreign-scope exclusion, canonical-name ambiguity and source preservation; it is not owner acceptance or a broad retrieval benchmark. See S67 and ACT-0200.
