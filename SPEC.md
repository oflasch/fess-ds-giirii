# Specification: Fess Data Store Plugin for *Gesetze im Internet*

Status: draft for review, 2026-10-01.

## 1. Purpose and scope

`fess-ds-giirii` is a data store plugin for codelibs Fess 15.7.0. It indexes the
German federal statutes that the Federal Ministry of Justice publishes as XML on
<https://www.gesetze-im-internet.de> (GII) and keeps the index current through a
scheduled Fess crawl job.

| Item | Value |
|---|---|
| Fess version | 15.7.0 (released `fess-parent` and `fess` artifacts) |
| Java level | 21 |
| Maven artifact | `fess-ds-giirii` |
| Package root | `de.oliverflasch.fess.ds.giirii` |
| Handler name | `GiiDataStore` |
| Corpus | 6,135 laws, about 250 MB of ZIP archives, an estimated 150,000 to 250,000 sections |

### 1.1 Out of scope

*Rechtsprechung im Internet* (RII, <https://www.rechtsprechung-im-internet.de>) is
currently not supported. Its `robots.txt` disallows all user agents except
`DG_JUSTICE_CRAWLER`, and every response carries the header `tdm-reservation: 1`.
Support requires a decision of the operator on the legal position. Appendix B
records the RII facts that a later version needs. The classes in `support` must
stay free of GII-specific assumptions where section 7 marks them as source-neutral.

A separate overview document per law is currently not produced; however, a law
without sections yields one document (section 3.1).

## 2. Source facts

All facts in this section were verified against the live site on 2026-10-01.
Appendix A lists the evidence.

### 2.1 Table of contents

`https://www.gesetze-im-internet.de/gii-toc.xml` is a 1.3 MB XML document with a
`DOCTYPE` that references an external DTD.

```xml
<items>
  <item>
    <title>Bürgerliches Gesetzbuch</title>
    <link>http://www.gesetze-im-internet.de/bgb/xml.zip</link>
  </item>
</items>
```

- Every link matches `http://www.gesetze-im-internet.de/{slug}/xml.zip`.
- The slug consists of the characters `a-z`, `0-9`, `_` and `-`.
- All 6,135 links are unique.
- The TOC carries no modification dates.
- The links use `http`. The server redirects `http` to `https` with status 302.
- A TOC entry can point to an archive that returns 404 (observed: `eu_fahrgrbusv`).

### 2.2 Archive

`https://www.gesetze-im-internet.de/{slug}/xml.zip` contains exactly one `*.xml`
entry and optionally image files (observed: 257 JPG files in `stvo_2013`).

- The server sends `ETag` and `Last-Modified`.
- A request with `If-None-Match` set to the current `ETag` returns 304.
- The largest observed archive is 1.0 MB; the largest observed XML is 2.5 MB (BGB).

### 2.3 Law XML

The root element `dokumente` contains one `norm` element per unit of the law.

| Kind of norm | Recognized by | Use |
|---|---|---|
| Header | first `norm`; `doknr` equals the `doknr` of `dokumente` | law metadata: `jurabk`, `amtabk`, `langue`, `kurzue`, `ausfertigung-datum`, `fundstelle`, `standangabe` |
| Structural unit | `metadaten/gliederungseinheit` | breadcrumb: `gliederungskennzahl`, `gliederungsbez`, `gliederungstitel` |
| Section | `metadaten/enbez` | one indexed document |

- Each `norm` carries `doknr` and `builddate` (`yyyyMMddHHmmss`).
- Section text is in `textdaten/text/Content`; footnotes are in `textdaten/fussnoten`.
- The text markup includes `P`, `BR`, `DL`/`DT`/`DD`/`LA`, `table`/`row`/`entry`,
  `IMG`, `pre`, `noindex`, `SUP`, `SUB`, `B`, `I`, `U`, `FnR`, `Footnote`.
- The DTD `gii-norm.dtd` declares parameter entities only. Documents use the five
  predefined XML entities. A parser that does not load the DTD reads every document.
- Section labels (`enbez`) repeat within a law that consists of articles with their
  own paragraph numbering (observed: 149 repeated labels in `bgbeg`).
- A law can consist of the header norm alone (observed: `euv_2022_612`,
  `moselschabgt2002abest`).

### 2.4 HTML pages

- `/{slug}/index.html` lists one link per section, in the order of the sections in
  the XML. The number of links equalled the number of sections in 32 of 33 sampled
  laws; the remaining law has no sections.
- The page name of a section cannot be computed from `enbez`: a normalization rule
  failed for 313 of 5,523 sampled sections (`(XXXX) §§ 3 bis 6` is
  `___3_bis_6.html`; `§ 1` in `bgbeg` is `art_224__1.html`).
- `/{slug}/{lawDoknr}.html` renders the whole law and contains an anchor named
  after the `doknr` of every norm.
- `/{slug}/` is the landing page of the law.

## 3. Document model

### 3.1 Documents

The plugin creates one Fess document per section norm. For a law without section
norms the plugin creates one document from the header norm.

### 3.2 URL

The URL identifies the document: Fess derives the document ID from `url`, the
roles and the virtual hosts (`CrawlingInfoHelper.generateId`).

1. The plugin fetches `/{slug}/index.html` of every law it re-indexes and extracts
   the relative section links in document order, without duplicates.
2. When the number of links equals the number of section norms, section *i*
   receives link *i*: `https://www.gesetze-im-internet.de/{slug}/{page}`.
3. Otherwise, and when the index page cannot be fetched, every section of that law
   receives the anchor URL
   `https://www.gesetze-im-internet.de/{slug}/{lawDoknr}.html#{normDoknr}`, and the
   plugin logs a warning that names the law and both counts.
4. The document of a law without sections receives
   `https://www.gesetze-im-internet.de/{slug}/`.

A law uses one URL scheme for all its sections. URLs within a law must be unique;
a law with duplicate URLs after step 2 falls back to step 3.

### 3.3 Fields

| Field | Value |
|---|---|
| `url` | see 3.2 |
| `title` | `{enbez} {jurabk}`, followed by ` – {titel}` when the norm has a title. Law-level document: `{langue} ({jurabk})` |
| `content` | plain text of `Content` (3.4). Law-level document: `standangabe` comments and footnote text |
| `important_content` | long title, short title, `jurabk`, `amtabk`, and the breadcrumb |
| `digest` | first `max_digest_length` characters of `content` |
| `content_length` | length of `content` in characters |
| `last_modified` | `builddate` of the norm, interpreted in `Europe/Berlin` |
| `timestamp` | time of indexing |
| `host`, `site` | `www.gesetze-im-internet.de` |
| `lang` | `de` |
| `mimetype`, `filetype` | `text/html`, `html` |
| `gii_law` | slug of the law |
| `gii_validator` | `ETag` of the archive; `Last-Modified` when the server sends no `ETag` |
| `gii_jurabk` | abbreviation of the law |
| `gii_enbez` | section label |
| `gii_doknr` | `doknr` of the norm |
| `gii_path` | breadcrumb: `gliederungsbez` and `gliederungstitel` of the enclosing structural units, outermost first, joined with ` > ` |

The enclosing structural units of a section are derived from
`gliederungskennzahl`: a structural unit encloses the following norms until a
structural unit with a `gliederungskennzahl` of equal or shorter length follows.

The fields `config_id`, `segment`, `created`, `boost`, `role` and `virtual_host`
come from `AbstractDataStore.store`. The plugin removes `expires` (section 4.1).

### 3.4 Text extraction

- Block elements (`P`, `DT`, `DD`, `LA`, `row`, `pre`, `Title`, `BR`) end with a
  line break. Table cells (`entry`) are separated by a space.
- `SUP`, `SUB`, `B`, `I`, `U`, `small` and `F` contribute their text inline.
- `noindex`, `IMG`, `FILE`, `FnR`, `Footnotes` and `fussnoten` contribute no text to
  `content`.
- The plugin removes control characters other than line break and tab, collapses
  runs of spaces, and trims lines.
- A section whose `content` is empty after extraction is indexed with its title.

### 3.5 Scripts

The plugin fills every field of 3.3 without a script. Each script entry of the
data store configuration is evaluated after the defaults and replaces or adds the
field it names. Scripts can reference all fields of 3.3 and additionally
`footnotes`, `law_title`, `law_short_title`, `law_date` (`ausfertigung-datum`),
`law_status` (list of `standangabe` comments) and `law_doknr`.

Scripts cannot change `url`, `gii_law`, `gii_validator`, `segment` and `config_id`;
the plugin sets these fields after script evaluation because the synchronization
depends on them.

### 3.6 Index mapping

At the start of every run the plugin registers `gii_law`, `gii_validator`,
`gii_jurabk`, `gii_enbez`, `gii_doknr` and `gii_path` as `keyword` fields on the
Fess update index. The registration is idempotent. The operator performs no index
setup. Displaying or faceting these fields in the Fess UI requires the
`query.additional.*` properties of Fess; the plugin does not change them.

## 4. Synchronization

### 4.1 Ownership of deletion

Fess deletes, after every data store run and inside a `finally` block, all
documents of the configuration whose `segment` differs from the current session
(`DataIndexHelper.DataCrawlingThread.deleteOldDocs`). With incremental
synchronization this deletes every unchanged document, and after a failed run it
deletes every law the run did not reach.

- `GiiDataStore` overrides `store` and sets the parameter `delete_old_docs` to
  `false` on the initial parameter map before it delegates to
  `AbstractDataStore.store`. A value configured by the operator is overwritten.
- `GiiDataStore` removes `expires` from the default data map, so that the Fess
  purge job retains documents of unchanged laws
  (`CrawlingInfoHelper.getDocumentExpires` supplies a default expiry).
- The plugin performs all deletions itself (4.4).

### 4.2 State

The synchronization state is the set of `(gii_law, gii_validator)` pairs of the
indexed documents of the configuration. The plugin reads it at the start of a run
with a composite aggregation filtered by `config_id`. An emptied index therefore
causes a complete re-index on the next run without operator action.

A law with more than one validator in the index is in an inconsistent state and
is treated as changed.

### 4.3 Run

1. Register the mapping (3.6) and load the state (4.2).
2. Fetch and parse the TOC. A TOC that cannot be fetched, exceeds its size limit,
   is not well-formed, contains no valid entry, or contains an invalid link ends
   the run with a `DataStoreException`. No deletion takes place.
3. Apply the `laws` filter and the `limit` parameter to the TOC entries.
4. Process the laws sequentially in TOC order while the data store is alive
   (`AbstractDataStore.alive`), waiting `read_interval` between laws:
   1. Request the archive. Unless `full=true`, and when the state holds exactly one
      validator for the law, send it as `If-None-Match` (or `If-Modified-Since`
      for a `Last-Modified` validator). A validator that starts with `"` or `W/`
      is an `ETag`.
   2. Status 304: the law is unchanged. Continue with the next law.
   3. Status 200: read the archive within its size limit, extract the XML (5.3),
      parse it (5.4), resolve the section URLs (3.2), and pass every document to
      `IndexUpdateCallback.store` with the new validator.
   4. The law is *completed* when every document of the law was stored without an
      exception.
5. Commit the callback and refresh the update index.
6. Delete stale sections (4.4).
7. Log one summary line: laws listed, unchanged, re-indexed, failed; documents
   stored; documents deleted; duration.

### 4.4 Deletion

**Stale sections.** For every completed law the plugin deletes the documents with
the `config_id` of the configuration, `gii_law` equal to the slug, and `segment`
different from the current session. A law that is not completed keeps all its
documents.

**Removed laws.** A law is removed when the state contains its slug and the TOC
does not. The plugin deletes all documents of removed laws under these conditions:

- The run is unrestricted: neither `laws` nor `limit` is set.
- The processing loop reached the end of the TOC (4.5).
- The number of removed laws is at most `max_deletion_ratio` times the number of
  laws in the state.

When the ratio condition fails, the plugin deletes no removed law, logs an error
that states both numbers, and records one entry through `FailureUrlService`. The
operator raises `max_deletion_ratio` for one run after verifying the TOC.

### 4.5 Failure handling

- A law that fails (status other than 200 or 304 after retries, I/O error, limit
  exceeded, malformed archive or XML) is recorded through `FailureUrlService` with
  the archive URL and the exception class, counted in `CrawlerStatsHelper`, and
  skipped. Its indexed documents and validator remain, so the next run requests it
  again. A 404 is a failure of this kind; the law stays indexed while the TOC
  lists it.
- `max_consecutive_failures` consecutive failed laws end the processing loop.
  Steps 5 to 7 of 4.3 still execute; removed laws are not deleted in that run.
- An exception from `IndexUpdateCallback.store` for one document fails the law.
- `stop()` ends the loop after the current law. Steps 5 to 7 still execute;
  removed laws are not deleted in that run.

### 4.6 Parameters

| Parameter | Default | Meaning |
|---|---|---|
| `purge` | `false` | `true` deletes all documents of the configuration and ends the run (4.7) |
| `full` | `false` | `true` re-indexes every law regardless of its validator |
| `laws` | empty | comma-separated slugs; restricts the run to these laws |
| `limit` | `0` | maximum number of laws processed; `0` is unlimited |
| `read_interval` | `200` | wait between laws in milliseconds |
| `user_agent` | Fess crawler User-Agent | `User-Agent` header of all requests |
| `max_digest_length` | `200` | length of `digest` in characters |
| `max_toc_size` | `16777216` | maximum TOC size in bytes |
| `max_zip_size` | `67108864` | maximum archive size in bytes |
| `max_xml_size` | `268435456` | maximum uncompressed XML size in bytes |
| `max_deletion_ratio` | `0.1` | see 4.4 |
| `max_consecutive_failures` | `20` | see 4.5 |

A parameter value that cannot be parsed or lies outside its valid range ends the
run with a `DataStoreException` that names the parameter. Unknown values are never
replaced by a default. `script_type` keeps its Fess meaning.

### 4.7 Purge

A run with `purge=true` removes the documents of the configuration from the index.
It replaces the run of 4.3:

1. Refresh the update index.
2. Delete all documents with the `config_id` of the configuration.
3. Log the number of deleted documents.

- A purge run sends no HTTP request and stores no document.
- `purge=true` together with `full=true`, `laws` or `limit` ends the run with a
  `DataStoreException` before any deletion.
- `max_deletion_ratio` does not apply to a purge run.
- A failed delete query is logged, recorded through `FailureUrlService`, and
  leaves the remaining documents for the next purge run.
- The field definitions of 3.6 remain in the index mapping.

The parameter stays in effect for every run until the operator removes it. A
scheduled run with `purge=true` on an empty configuration deletes nothing.

## 5. Security and robustness

### 5.1 URL validation

- The plugin requests three URL shapes on the host `www.gesetze-im-internet.de`:
  the TOC, `/{slug}/xml.zip` and `/{slug}/index.html`.
- A TOC link must match
  `^https?://www\.gesetze-im-internet\.de/([a-z0-9_-]+)/xml\.zip$`. The plugin
  extracts the slug and builds every request URL itself with the scheme `https`.
- Section links from an index page must match `^[A-Za-z0-9_.-]+\.html$`. A link
  outside this pattern causes the anchor fallback for the law (3.2).
- The HTTP client does not follow redirects. A 3xx response is a failure.
- The plugin does not read files and does not accept a URL parameter from the
  data store configuration.

### 5.2 HTTP

- Connect timeout 30 s; request timeout 120 s.
- Every request sends the configured `User-Agent`; a blank value is replaced by
  the Fess crawler User-Agent and then by a fixed fallback.
- Status 429 and 503 are retried up to 3 times. The wait follows `Retry-After`,
  capped at 5 minutes, and is 1 s without the header.
- Every response body is read through a counting stream that fails once the
  applicable size limit is exceeded. Error bodies are drained up to 4 KiB.
- Interruption of the thread ends the request and the run.

### 5.3 Archive

- The archive is read in memory within `max_zip_size`.
- The plugin reads the first entry whose name ends with `.xml` and ignores all
  other entries. Entry names are never used as file system paths. Nothing is
  written to disk.
- Inflation stops with a failure once the output exceeds `max_xml_size`.
- An archive without an XML entry is a failure.

### 5.4 XML

- TOC and law XML are parsed with StAX. `SUPPORT_DTD` and
  `IS_SUPPORTING_EXTERNAL_ENTITIES` are `false`; `ACCESS_EXTERNAL_DTD` and
  `ACCESS_EXTERNAL_SCHEMA` are empty; no `XMLResolver` resolves external resources.
- A document that references an undeclared entity is a failure.
- The index page is scanned for `href` attributes with a bounded regular
  expression; it is not parsed as XML.

### 5.5 Resource bounds

- The plugin holds the TOC entries, the state map and one law in memory.
- The run is single-threaded. Daily cost with no changes: one TOC request and
  6,135 conditional requests.
- Log messages are parameterized and guarded. They contain slugs, counts, status
  codes and validators. They do not contain document text.

## 6. Configuration in Fess

Data store configuration (Crawler > Data Store):

| Setting | Value |
|---|---|
| Handler Name | `GiiDataStore` |
| Parameter | empty for the default behaviour; see 4.6 |
| Script | empty for the default fields; see 3.5 |

One configuration indexes the whole corpus. Two configurations must not index the
same law: their documents would share IDs when roles and virtual hosts are equal.

### 6.1 Coexistence with other crawlers

The plugin shares the Fess document index with Web, File System and other data
store configurations.

- **Deletion scope.** Every delete query of the plugin contains the `config_id`
  of its own configuration (4.4). Documents of other configurations are never
  matched.
- **Parameter scope.** Fess creates one parameter map per data store
  configuration (`DataIndexHelper.doCrawl`). The forced `delete_old_docs=false`
  (4.1) applies to the `GiiDataStore` configuration only.
- **Expiry of other documents.** The Fess purge job deletes all documents whose
  `expires` date has passed (`PurgeDocJob.execute`). Documents of Web and File
  System configurations keep their `expires` field and expire as configured.
- **Index mapping.** The registration of the `gii_*` fields (3.6) adds six field
  definitions to the mapping of the index. Only documents of the plugin carry
  values in these fields. Existing documents are not rewritten.

Constraints for the operator:

- **Host exclusion.** Web crawl configurations must exclude
  `www.gesetze-im-internet.de`. A Web crawl of that host produces documents with
  the URLs of the plugin's documents; Fess derives the document ID from the URL,
  so both configurations overwrite each other's documents on every run.
- **Removal of the configuration.** Documents of the plugin carry no `expires`
  field, and Fess 15.7.0 does not delete documents when a data store
  configuration is deleted or disabled. Before deleting or disabling the
  configuration, the operator must set `purge=true` and start one crawl run
  (4.7).
- **Re-indexing after a purge.** The operator must remove `purge=true` before the
  next regular run. That run finds an empty state and indexes the whole corpus.

## 7. Components

| Unit | Responsibility | Source-neutral |
|---|---|---|
| `GiiDataStore` | parameters, run (4.3), purge (4.7), field assembly (3.3), scripts, statistics, failure recording | no |
| `support.HttpFetcher` | conditional, bounded, retrying GET on one allowed host; returns status, validator and body | yes |
| `support.TocParser` | TOC stream to `List<LawRef>`; validates links (5.1) | no |
| `support.LawArchive` | archive bytes to XML bytes within limits (5.3) | yes |
| `support.LawParser` | XML to `Law` (metadata, list of `Section`) | no |
| `support.NormTextExtractor` | StAX events of `Content` to plain text (3.4) | no |
| `support.SectionLinkResolver` | index page and `Law` to section URLs (3.2) | no |
| `support.IndexState` | mapping, state query, delete queries; the only class that uses the search engine client | yes (field names are constructor arguments) |
| `support.LawRef`, `Law`, `Section`, `RunSummary` | immutable records | no |

`GiiDataStore` is registered in `src/main/resources/fess_ds++.xml` with
`postConstruct name="register"`. `GiiDataStore` creates the support objects per
run from the parameters; it holds no state between runs.

## 8. Tests

`mvn test` must pass offline. Tests never contact `gesetze-im-internet.de`.
Assertions are JUnit 5 with the message last.

**Fixtures** under `src/test/resources/fixtures`: a TOC excerpt; GG; a BGB excerpt
with structural units and a repealed range; a `bgbeg` excerpt with repeated labels;
a law with a table and images; a header-only law; matching index pages; archives
built from these files.

**Unit tests**

- `TocParser`: valid TOC; link on another host; link with path traversal; empty
  TOC; oversize TOC; `DOCTYPE` with an external entity that points to a canary
  file, asserting that the canary text is absent from the result.
- `LawArchive`: single XML; XML among images; no XML entry; archive that inflates
  beyond the limit; entry named `../../x.xml`.
- `LawParser` and `NormTextExtractor`: metadata; breadcrumb nesting; table and list
  text; `noindex` and footnotes excluded; control characters removed; header-only
  law; external entity canary.
- `SectionLinkResolver`: equal counts; unequal counts; duplicate links; link
  outside the pattern; missing index page.
- `HttpFetcher` against a loopback `com.sun.net.httpserver.HttpServer`: 200 with
  validator; 304; 404; 429 with `Retry-After`; 503 exhausted; redirect refused;
  body beyond the limit; blank User-Agent replaced.

**Synchronization tests** with a recording `IndexUpdateCallback` and an in-memory
`IndexState`:

- An unchanged law is neither stored nor deleted.
- A changed law is stored; sections that left it are deleted.
- A law that fails keeps its documents and its validator.
- A law with two validators in the state is requested unconditionally.
- A TOC that lost more laws than `max_deletion_ratio` deletes nothing and records
  a failure.
- A run with `laws` or `limit` deletes no removed law.
- `store` sets `delete_old_docs=false`, and stored documents carry no `expires`.
- A script cannot change `url`, `gii_law`, `gii_validator`, `segment`, `config_id`.
- An invalid parameter value ends the run with a `DataStoreException`.
- A run with `purge=true` deletes the documents of its own `config_id`, keeps
  documents of another `config_id`, sends no HTTP request and stores no document.
- `purge=true` combined with `full=true`, `laws` or `limit` ends the run with a
  `DataStoreException` and deletes nothing.

**DI wiring test**: the container resolves `GiiDataStore` through
`DataStoreFactory` by its handler name.

**End-to-end check** in a local Fess 15.7.0 (`docker-fess`), outside `mvn test`:

1. Run with `laws=gg,bgb`: about 2,750 documents; a search for
   `Kaufvertrag` returns `§ 433 BGB` linking to `bgb/__433.html`.
2. Second run: no archive download, no stored document, no deletion.
3. Change `gii_validator` of the GG documents in the index, run again: GG is
   re-indexed, BGB is skipped.
4. Unrestricted run: record duration, document count and failures.

## 9. Build and repository changes

- `pom.xml`: `artifactId` `fess-ds-giirii`, name and SCM updated, parent
  `fess-parent` 15.7.0. Remove the Sweble dependencies, the shade plugin,
  `commons-compress`, `jackson-databind`, the snapshot repository and the
  `distributionManagement` block. The plugin has no bundled dependency.
- Delete `src/main/java/org/codelibs/fess/ds/wikipedia`, its tests, the fixtures
  and `src/test/resources/wikitext`.
- `src/main/resources/fess_ds++.xml`: register `giiDataStore`.
- `README.md`: installation, configuration (section 6), parameters (4.6), fields
  (3.3), scripts (3.5).
- `.github/workflows/maven.yml`: remove the checkout and installation of
  `fess-parent`.
- `IDEA.md` is deleted.

The rules of `AGENTS.md` on build, formatting, license header, Javadoc, Java style,
logging, code hygiene and tests apply. Its sections on FEDR (package root
`de.oliverflasch.fedr`, `fess_api++.xml`, document ACLs, SSE, LLM configuration)
describe another project and do not apply to this plugin.

## Appendix A: Evidence

| Claim | Evidence |
|---|---|
| TOC shape, 6,135 unique links, slug charset | download and analysis of `gii-toc.xml` |
| `http` redirects to `https` | `HEAD http://…/bgb/xml.zip` returned 302 |
| Conditional requests | `If-None-Match` with the current `ETag` returned 304 for `bgb/xml.zip` |
| Archive contents | listing of `bgb`, `gg`, `bimschv_1_2010`, `stvo_2013`, `estg`, `bbesg` |
| TOC entry with 404 | `eu_fahrgrbusv/xml.zip` |
| Norm kinds and counts | BGB: 2,841 norms, 2,551 with `enbez`, 289 structural units |
| DTD without general entities | `dtd/1.01/gii-norm.dtd` |
| Index links pair with sections by position | 6 laws, 4,865 sections: link and section counts are equal; 4,577 positional pairs match the naming rule, and the other 288 are the rule's known failures (repealed ranges, repeated labels in `bgbeg`) |
| Naming rule fails | 313 of 5,523 sections in 33 laws |
| Anchors per norm | `bgb/BJNR001950896.html` contains `BJNR001950896BJNE042602377` |
| Corpus size | mean archive size of 60 random laws, extrapolated |
| Stale deletion in `finally` | `DataIndexHelper.DataCrawlingThread.process` and `deleteOldDocs`, Fess 15.7.0 |
| Default fields and `expires` | `AbstractDataStore.store`, `CrawlingInfoHelper.getDocumentExpires`, Fess 15.7.0 |
| Document ID from URL | `CrawlingInfoHelper.generateId`, Fess 15.7.0 |
| Parameter map per configuration | `DataIndexHelper.doCrawl` creates a `DataStoreParams` per `DataConfig`, Fess 15.7.0 |
| Purge by `expires` | `PurgeDocJob.execute` deletes by a range query on `expires`, Fess 15.7.0 |
| No deletion on configuration removal | `IndexingHelper.deleteByConfigId` has no caller in Fess 15.7.0 |

## Appendix B: RII facts for a later version

- `rii-toc.xml` is 23 MB and lists 84,423 decisions since 2010 with `gericht`,
  `entsch-datum`, `aktenzeichen`, `link` and `modified`. The `modified` timestamp
  identifies changed decisions (20 to 40 per day) from the TOC alone.
- The archives answer every request with status 200 and a `Last-Modified` equal to
  the request time. Conditional requests have no effect.
- The document root is `dokument` with `doknr`, `ecli` (frequently empty),
  `gertyp`, `spruchkoerper`, `entsch-datum`, `aktenzeichen`, `doktyp`, `norm`,
  `titelzeile`, `leitsatz`, `tenor`, `tatbestand`, `entscheidungsgruende`, `gruende`.
- A decision page is reachable through
  `https://www.rechtsprechung-im-internet.de/jportal/?quelle=jlink&docid={doknr}&psml=bsjrsprod.psml&max=true`.
- `robots.txt` contains `User-agent: *` with `Disallow: /`; every response carries
  `tdm-reservation: 1`.
