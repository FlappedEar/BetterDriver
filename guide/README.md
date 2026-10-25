# End-user guide

The BetterDriver user guide, published at <https://flappedear.github.io/BetterDriver/> (FBBD-327).

- Pages: `docs/*.md`, in the order of `nav` in `mkdocs.yml`. Built with
  [MkDocs](https://www.mkdocs.org/) and the Material theme, pinned in `requirements.txt`.
- Pictures: only the app's screenshot goldens (`core-ui` and `feature-track`
  `src/test/screenshots`), copied into `docs/img/` at build time, so the guide always shows the
  current screens. No mockups, and no real recordings or locations: the repository is public.
- Mark a feature merged on `main` but not yet released with a `!!! note "Not released yet"` box,
  and drop the box when the release ships it.
- `.github/workflows/guide.yml` builds the site with `--strict` on pull requests that touch it
  and publishes it to GitHub Pages from `main`.

Preview locally:

```
pip install -r guide/requirements.txt
mkdir -p guide/docs/img && cp core-ui/src/test/screenshots/*.png feature-track/src/test/screenshots/*.png guide/docs/img/
mkdocs serve -f guide/mkdocs.yml
```
