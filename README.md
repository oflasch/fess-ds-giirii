GiIRiI Data Store Plugin for codelibs Fess
[![Java CI with Maven](https://github.com/oflasch/fess-ds-giirii/actions/workflows/maven.yml/badge.svg)](https://github.com/oflasch/fess-ds-giirii/actions/workflows/maven.yml)
==========================

Copyright 2026 Oliver Flasch

## Overview

This is a German federal law data store plugin for the free enterprise search
engine codelibs Fess. It indexes the federal statutes that
<https://www.gesetze-im-internet.de> publishes as XML: about 6,100 laws with about 109,000 sections.

- Each section (§, Artikel, Anlage, Eingangsformel) is one search result that
  links to its page on gesetze-im-internet.de.
- A scheduled crawl keeps the index current. It downloads only the laws that
  changed since the previous run.
- The plugin removes sections and laws that left the site.

<https://www.rechtsprechung-im-internet.de> is currently not supported; `SPEC.md`
section 1.1 states the reason. `SPEC.md` is the specification of the plugin.

## Requirements

- Fess 15.7.0
- Java 21
- Outbound HTTPS access from the Fess crawler to `www.gesetze-im-internet.de`

## Build

```bash
mvn clean package
```

The build produces `target/fess-ds-giirii-15.7.0-SNAPSHOT.jar`. The plugin
bundles no dependency. `mvn test` runs offline.

## Installation

Releases are published on Maven Central as
`de.oliverflasch:fess-ds-giirii:<Fess version>-<plugin version>` and on the
[GitHub releases page](https://github.com/oflasch/fess-ds-giirii/releases).
Version `15.7.0-1.0.1` is plugin version 1.0.1 for Fess 15.7.0.

### From a downloaded JAR

1. Copy `fess-ds-giirii-<version>.jar` to `app/WEB-INF/plugin/` of the Fess
   installation (`/usr/share/fess/app/WEB-INF/plugin/` in the Docker image).
2. Restart Fess.

### From the Fess admin UI

1. Append `https://repo.maven.apache.org/maven2/de/oliverflasch/` to the
   comma-separated list `plugin.repositories` in `fess_config.properties` and
   restart Fess.
2. Open *System > Plugin*, select *Install*, choose `fess-ds-giirii` and
   install it. Fess lists the versions that start with its own major and minor
   version.
3. Restart Fess.

## Configuration

Create one data store configuration under *Crawler > Data Store*:

| Setting | Value |
|---|---|
| Name | for example `Gesetze im Internet` |
| Handler Name | `GiiDataStore` |
| Parameter | empty, or parameters from the table below |
| Script | empty, or field overrides (see [Scripts](#scripts)) |

The *Default Crawler* job, or a job of its own, runs the configuration. One run
per day matches the update cycle of the site.

Constraints:

- One configuration indexes the whole corpus. Two configurations must not index
  the same law.
- Web crawl configurations must exclude `www.gesetze-im-internet.de`. A Web
  crawl of that host and this plugin overwrite each other's documents.

### Parameters

One `name=value` pair per line. All parameters are optional.

| Parameter | Default | Meaning |
|---|---|---|
| `purge` | `false` | `true` deletes all documents of the configuration and ends the run |
| `full` | `false` | `true` re-indexes every law regardless of changes |
| `laws` | empty | comma-separated law slugs, for example `gg,bgb`; restricts the run to these laws |
| `limit` | `0` | maximum number of laws processed; `0` is unlimited |
| `read_interval` | `200` | wait between laws in milliseconds |
| `user_agent` | Fess crawler User-Agent | `User-Agent` header of all requests |
| `max_digest_length` | `200` | length of the result snippet source (`digest`) in characters |
| `max_toc_size` | `16777216` | maximum size of the table of contents in bytes |
| `max_zip_size` | `67108864` | maximum size of a law archive in bytes |
| `max_xml_size` | `268435456` | maximum size of an unpacked law document in bytes |
| `max_deletion_ratio` | `0.1` | largest share of indexed laws that one run removes |
| `max_consecutive_failures` | `20` | number of consecutive failed laws that ends the run |

The slug of a law is its path segment on the site:
`https://www.gesetze-im-internet.de/bgb/` has the slug `bgb`.

An invalid value ends the run with an error that names the parameter.
`purge=true` cannot be combined with `full`, `laws` or `limit`.

### Indexed fields

| Field | Value |
|---|---|
| `url` | page of the section, for example `https://www.gesetze-im-internet.de/bgb/__433.html` |
| `title` | `§ 433 BGB – Vertragstypische Pflichten beim Kaufvertrag` |
| `content` | text of the section |
| `important_content` | titles and abbreviations of the law, and the breadcrumb |
| `digest` | first `max_digest_length` characters of `content` |
| `last_modified` | build date of the section on the site |
| `timestamp` | time of indexing |
| `host`, `site`, `lang`, `mimetype`, `filetype`, `content_length` | `www.gesetze-im-internet.de`, `de`, `text/html`, `html`, length of `content` |
| `gii_law` | slug of the law |
| `gii_validator` | `ETag` of the law archive; the plugin compares it on the next run |
| `gii_jurabk` | abbreviation of the law, for example `BGB` |
| `gii_enbez` | label of the section, for example `§ 433` |
| `gii_doknr` | document number of the section |
| `gii_path` | breadcrumb, for example `Buch 2 Recht der Schuldverhältnisse > Abschnitt 8 Einzelne Schuldverhältnisse` |

The plugin registers the `gii_*` fields as `keyword` fields in the index
mapping at the start of every run. Displaying or faceting them in the Fess UI
requires the `query.additional.*` properties of Fess.

A law that has no sections yields one document that links to the landing page
of the law.

### Scripts

The plugin fills all fields without a script. A script entry replaces or adds
the field it names:

```
title=gii_enbez + " " + gii_jurabk
label="Bundesrecht"
```

Scripts can reference every field of the table above and additionally
`footnotes`, `law_title`, `law_short_title`, `law_date`, `law_status` and
`law_doknr`. Scripts cannot change `url`, `gii_law`, `gii_validator`, `segment`,
`config_id` and `expires`.

## Operation

### First run

The first run downloads all archives (about 250 MB) and one index page per law.
With the default `read_interval` it took 36 minutes for 6,135 laws and 108,651
documents (2026-10-02); the index grew by about 500 MB. A trial run with
`laws=gg,bgb` indexes 2,756 documents in about 10 seconds.

### Scheduled runs

Every run requests each law with the stored `ETag`. The site answers 304 for an
unchanged law, and the plugin skips it. A changed law is downloaded and
re-indexed, and sections that left it are deleted.

A run without changes took 25 minutes for 6,135 laws (2026-10-02), of which
about 20 minutes are the default `read_interval` of 200 ms per law.

The crawler log (`fess-crawler.log`) contains one summary line per run:

```
Run finished in 512340 ms: 6135 laws listed, 6129 unchanged, 5 re-indexed, 1 failed; 412 documents stored, 3 deleted.
```

A law that fails keeps its indexed documents, appears under *System Info >
Failure URL*, and is requested again on the next run.

### Removed laws

A law that the table of contents no longer lists is deleted from the index.
When more than `max_deletion_ratio` of the indexed laws are missing, the plugin
treats the table of contents as defective, deletes nothing, and records a
failure. After verifying that the laws are gone, raise `max_deletion_ratio` for
one run.

### Removing the plugin's documents

Documents of the plugin carry no expiry, and Fess does not delete them when the
data store configuration is deleted. Before deleting or disabling the
configuration:

1. Set the parameter `purge=true`.
2. Start one crawl run. It deletes all documents of the configuration.
3. Delete the configuration, or remove `purge=true` to index the corpus again.

### Deletion by Fess

Fess deletes all documents of a data store configuration that the current run
did not store. With incremental runs this would delete every unchanged law. The
plugin therefore forces the Fess parameter `delete_old_docs` to `false` for its
own configuration, removes the expiry from its documents, and performs all
deletions itself. Other configurations are not affected.

## Limits and safeguards

- The plugin requests `www.gesetze-im-internet.de` over HTTPS only, does not
  follow redirects, and builds every request URL from a validated law slug.
- Response bodies, archives and unpacked documents are bounded by the
  `max_*_size` parameters. Archives are read in memory; nothing is written to
  disk.
- XML is parsed without loading DTDs or external entities.
- Throttled responses (429, 503) are retried three times and honour
  `Retry-After` up to five minutes.

## Development

```bash
mvn test                                            # offline unit and wiring tests
mvn impsort:sort formatter:format license:format    # before committing
mvn clean package                                   # JAR, sources, Javadoc
```

The tests run against a loopback HTTP server and trimmed copies of real law
documents under `src/test/resources/fixtures`. `AGENTS.md` states the coding
rules.

## License

Apache License 2.0, see `LICENSE`.
