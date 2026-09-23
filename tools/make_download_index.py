#!/usr/bin/env python3
"""Build dl.ristos.org/index.html from a published release directory.

Why this exists. The download page was a single hand-written HTML file, untracked and outside version
control, with the build number typed into it twelve times. Nothing in any script or runbook touched it:
`set_release.sh` rewrites README.md, image/INSTALL.md, CHANGELOG.md and releases/CURRENT_BUILD;
`ota_publish.sh` writes only to the OTA bucket; `sign_public.sh` contains no upload at all. So
2026092200 was built, signed, uploaded, promoted to stable -- and the index still advertised 2026090701,
two releases behind. Anyone arriving at the site was handed the older build.

That is worse than a stale number, because R2 serves no directory listing: `https://dl.ristos.org/<BN>/`
is a 404. The root index is the ONLY way to discover a release, which also makes it the only way to
reach the kernel source, and NOTICE promises that source is "reachable from the same download index"
under GPLv2 section 3(a).

The list of files is not typed here either. SHA256SUMS is the signed record of what the release
contains, so it is the authority: every name in it appears on the page, and sizes come from the bytes
on disk rather than from a number someone updated by hand. The old page claimed 3.50 GiB for a factory
zip that was 1.96 GiB.

    python3 tools/make_download_index.py --dir <published dir> --build 2026092300 \\
        --spl 2026-09-01 --out index.html
    python3 tools/make_download_index.py --selftest
"""

import argparse
import html
import os
import re
import sys
import tempfile

# Sections, in page order. A name matching no pattern still appears, under "Other files": silently
# dropping a signed artefact is the one failure this tool must not have.
SECTIONS = [
    ('Operating system', [
        r'.*-factory-.*\.zip$',
        r'.*-ota_update-.*\.zip$',
        r'^INSTALL\.md$',
        r'.*-img-.*\.zip$',
        r'.*-install-.*\.zip$',
    ]),
    ('Source code', [
        r'^kernel-source-.*\.tar\.gz$',
        r'^kernel-source-.*\.tar\.gz\.sha256$',
    ]),
    ('Verifying what you downloaded', [
        r'^SHA256SUMS$',
        r'^SHA256SUMS\.minisig$',
    ]),
]

STYLE = """  :root {
    --ground: #fbfaf8; --panel: #ffffff; --line: #e3e0d9; --ink: #1c1b19;
    --ink-soft: #5d594f; --ink-faint: #8a8478; --accent: #8a5a2b; --accent-soft: #f3ead9;
    --warn-ink: #7a3b12; --warn-bg: #fdf2e6; --warn-line: #e8c9a4;
    --mono: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
    --sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  }
  @media (prefers-color-scheme: dark) {
    :root:not([data-theme="light"]) {
      --ground: #161513; --panel: #1f1e1b; --line: #34322d; --ink: #eceae5;
      --ink-soft: #b0aba1; --ink-faint: #837d72; --accent: #d79a5b; --accent-soft: #2c2419;
      --warn-ink: #edb787; --warn-bg: #2b1f14; --warn-line: #543a24;
    }
  }
  :root[data-theme="dark"] {
    --ground: #161513; --panel: #1f1e1b; --line: #34322d; --ink: #eceae5;
    --ink-soft: #b0aba1; --ink-faint: #837d72; --accent: #d79a5b; --accent-soft: #2c2419;
    --warn-ink: #edb787; --warn-bg: #2b1f14; --warn-line: #543a24;
  }
  :root[data-theme="light"] {
    --ground: #fbfaf8; --panel: #ffffff; --line: #e3e0d9; --ink: #1c1b19;
    --ink-soft: #5d594f; --ink-faint: #8a8478; --accent: #8a5a2b; --accent-soft: #f3ead9;
    --warn-ink: #7a3b12; --warn-bg: #fdf2e6; --warn-line: #e8c9a4;
  }

  * { box-sizing: border-box; }
  body { margin: 0; background: var(--ground); color: var(--ink); font: 16px/1.6 var(--sans);
         -webkit-font-smoothing: antialiased; }
  .wrap { max-width: 52rem; margin: 0 auto; padding: 3rem 1.25rem 5rem; }
  header { border-bottom: 1px solid var(--line); padding-bottom: 1.75rem; margin-bottom: 2.5rem; }
  h1 { font-size: 1.9rem; letter-spacing: -0.02em; margin: 0 0 .4rem; text-wrap: balance; }
  .sub { color: var(--ink-soft); margin: 0; }
  .build { display: inline-block; margin-top: 1rem; padding: .3rem .6rem;
           background: var(--accent-soft); color: var(--accent); border-radius: 4px;
           font: 600 .8rem/1 var(--mono); letter-spacing: .02em; }
  h2 { font-size: .78rem; text-transform: uppercase; letter-spacing: .09em; color: var(--ink-faint);
       margin: 2.75rem 0 .9rem; font-weight: 600; }
  h2:first-of-type { margin-top: 0; }
  .files { border: 1px solid var(--line); border-radius: 8px; background: var(--panel);
           overflow: hidden; }
  .f { display: flex; align-items: baseline; gap: 1rem; padding: .85rem 1.1rem;
       border-top: 1px solid var(--line); }
  .f:first-child { border-top: 0; }
  .f a { color: var(--accent); text-decoration: none; font-family: var(--mono); font-size: .875rem;
         word-break: break-all; }
  .f a:hover { text-decoration: underline; }
  .f a:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; border-radius: 2px; }
  .size { margin-left: auto; color: var(--ink-faint); font: .8rem/1 var(--mono);
          font-variant-numeric: tabular-nums; white-space: nowrap; }
  .note { color: var(--ink-soft); font-size: .9rem; margin: .7rem 0 0; }
  .warn { background: var(--warn-bg); border: 1px solid var(--warn-line); color: var(--warn-ink);
          border-radius: 8px; padding: 1rem 1.15rem; margin: 1rem 0 0; }
  .warn strong { display: block; margin-bottom: .3rem; }
  .warn p { margin: 0; font-size: .92rem; }
  pre { background: var(--panel); border: 1px solid var(--line); border-radius: 8px;
        padding: .9rem 1.1rem; overflow-x: auto; font: .84rem/1.55 var(--mono); color: var(--ink); }
  a.plain { color: var(--accent); }
  footer { margin-top: 3.5rem; padding-top: 1.5rem; border-top: 1px solid var(--line);
           color: var(--ink-faint); font-size: .85rem; }
  footer a { color: var(--ink-soft); }"""


def human(n):
    """Sizes as the page has always shown them: GiB/MiB/KiB, computed from the real bytes."""
    if n >= 1 << 30:
        return '%.2f GiB' % (n / (1 << 30))
    if n >= 1 << 20:
        return '%.0f MiB' % (n / (1 << 20))
    if n >= 1 << 10:
        return '%.0f KiB' % (n / (1 << 10))
    return '%d B' % n


def read_sha256sums(path):
    """The signed record of what the release contains, so the authority on what the page must list."""
    names = []
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            parts = line.split()
            if len(parts) < 2:
                continue
            name = parts[1]
            if name.startswith('./'):
                name = name[2:]
            names.append(name)
    return names


def classify(names):
    placed, out = set(), []
    for title, patterns in SECTIONS:
        rows = []
        for pat in patterns:
            for n in names:
                if n not in placed and re.match(pat, n):
                    rows.append(n)
                    placed.add(n)
        if rows:
            out.append((title, rows))
    leftover = [n for n in names if n not in placed]
    if leftover:
        out.append(('Other files', sorted(leftover)))
    return out


def render(build, spl, sections, sizes):
    def rows(names):
        return '\n'.join(
            '  <div class="f"><a href="/%s/%s">%s</a><span class="size">%s</span></div>'
            % (build, html.escape(n, quote=True), html.escape(n), human(sizes[n]))
            for n in names
        )

    blocks = []
    for title, names in sections:
        blocks.append('<h2>%s</h2>\n<div class="files">\n%s\n</div>'
                      % (html.escape(title), rows(names)))
        if title == 'Operating system':
            blocks.append(
                '<p class="note">The factory image is the one to use for a first install. It contains '
                'everything the phone needs, including Google&rsquo;s Pixel firmware &mdash; the '
                'bootloader and radio images &mdash; so there is no separate stock image to flash '
                'first. Unzip it and run <code>flash-all.sh</code>.</p>')
        elif title == 'Source code':
            blocks.append(
                '<p class="note">Complete corresponding source for the kernel, offered here under '
                'GPLv2 &sect;3(a) &mdash; same host, same terms, no request needed. The GPL- and '
                'LGPL-licensed userspace components are available for this build on request, under the '
                'written offer in NOTICE. The rest of the OS source is at '
                '<a class="plain" href="https://github.com/rist-os/rist-os-handset">'
                'github.com/rist-os/rist-os-handset</a>.</p>')

    return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RistOS downloads</title>
<style>
%s
</style>
</head>
<body>
<div class="wrap">

<header>
  <h1>RistOS</h1>
  <p class="sub">A de-Googled Android build for the Pixel&nbsp;10a (<code>stallion</code>).</p>
  <span class="build">build %s &middot; security patch %s</span>
</header>

<div class="warn">
  <strong>Flashing this replaces everything on the phone.</strong>
  <p>All data is erased, and the process cannot be undone from the phone itself. Read
  <a class="plain" href="/%s/INSTALL.md">INSTALL.md</a> before you start &mdash; including
  what it says about emergency calling.</p>
</div>

%s

<div class="warn">
  <strong>Do not take the signing key from this website.</strong>
  <p>The public key is published only on
  <a class="plain" href="https://github.com/rist-os/rist-os-handset">github.com/rist-os/rist-os-handset</a>.</p>
</div>

<pre>minisign -Vm SHA256SUMS -P &lt;public key from GitHub&gt;
sha256sum --ignore-missing -c SHA256SUMS</pre>

<footer>
  RistOS is not affiliated with, endorsed by, or supported by Google. Pixel is a trademark of
  Google&nbsp;LLC. This build has been modified and is not Google&rsquo;s software.<br>
  Source, licences and security contact:
  <a href="https://github.com/rist-os/rist-os-handset">github.com/rist-os/rist-os-handset</a>
</footer>

</div>
</body>
</html>
""" % (STYLE, html.escape(build), html.escape(spl), html.escape(build), '\n\n'.join(blocks))


def build_index(directory, build, spl):
    sums = os.path.join(directory, 'SHA256SUMS')
    if not os.path.isfile(sums):
        raise SystemExit('no SHA256SUMS in %s. It is the signed record of what the release '
                         'contains, and this page is generated from it.' % directory)
    names = read_sha256sums(sums)
    if not names:
        raise SystemExit('SHA256SUMS in %s lists nothing.' % directory)

    missing, sizes = [], {}
    for n in names:
        f = os.path.join(directory, n)
        if not os.path.isfile(f):
            missing.append(n)
        else:
            sizes[n] = os.path.getsize(f)
    if missing:
        raise SystemExit('SHA256SUMS names files that are not in %s: %s\nThe page would link to '
                         '404s.' % (directory, ', '.join(missing)))

    # GPLv2 section 3(a). NOTICE says the index is authoritative for what accompanies a release, so a
    # release whose kernel source is not on the page has not been offered at all. 2026092200's ledger
    # entry records no kernel archive; do not repeat that silently.
    if not any(n.startswith('kernel-source-') for n in names):
        raise SystemExit('no kernel-source-* archive in SHA256SUMS. NOTICE promises the kernel source '
                         'is published alongside each release and reachable from this index (GPLv2 '
                         'section 3(a)), so this page must link it. Run '
                         'image/scripts/archive_kernel_source.sh while the build tree still exists.')

    page = render(build, spl, classify(names), sizes)

    # Every signed artefact must be reachable, and no other build may be advertised.
    for n in names:
        if ('/%s/%s' % (build, n)) not in page:
            raise SystemExit('generated page does not link %s' % n)
    others = {m for m in re.findall(r'\b20\d{8}\b', page)} - {build}
    if others:
        raise SystemExit('generated page mentions other builds: %s' % ', '.join(sorted(others)))
    return page


def selftest():
    failures = []

    def check(name, ok, detail=''):
        print('  selftest: %-46s %s' % (name, 'ok' if ok else 'FAILED ' + detail))
        if not ok:
            failures.append(name)

    with tempfile.TemporaryDirectory() as tmp:
        d = os.path.join(tmp, '2026092300')
        os.makedirs(d)
        files = {
            'stallion-factory-2026092300.zip': 2105928542,
            'stallion-ota_update-2026092300.zip': 1420466009,
            'INSTALL.md': 20225,
            'stallion-img-2026092300.zip': 1954000000,
            'kernel-source-stallion-2026092300.tar.gz': 7541254215,
            'kernel-source-stallion-2026092300.tar.gz.sha256': 107,
            'SHA256SUMS': 812,
            'SHA256SUMS.minisig': 292,
        }
        for n, size in files.items():
            with open(os.path.join(d, n), 'wb') as fh:
                fh.truncate(size)
        with open(os.path.join(d, 'SHA256SUMS'), 'w', encoding='utf-8') as fh:
            for n in files:
                fh.write('%s  ./%s\n' % ('0' * 64, n))
        # SHA256SUMS' own size changed when it was written; re-stat happens inside build_index.
        page = build_index(d, '2026092300', '2026-09-01')

        check('every signed artefact is linked',
              all(('/2026092300/%s' % n) in page for n in files))
        check('no other build number appears', not ({m for m in re.findall(r'\b20\d{8}\b', page)}
                                                    - {'2026092300'}))
        check('the kernel source is on the page',
              'kernel-source-stallion-2026092300.tar.gz' in page)
        check('sizes come from the bytes, not a typed number', '1.96 GiB' in page,
              '(expected the real factory size, not 3.50 GiB)')
        check('the verify recipe tolerates a partial download',
              'sha256sum --ignore-missing -c SHA256SUMS' in page)
        check('SHA256SUMS is listed once, not twice',
              page.count('>SHA256SUMS<') == 1)
        check('the signing-key warning survives',
              'Do not take the signing key from this website' in page)

        # A release with no kernel archive must be refused, not quietly published.
        d2 = os.path.join(tmp, 'nokernel')
        os.makedirs(d2)
        with open(os.path.join(d2, 'SHA256SUMS'), 'w', encoding='utf-8') as fh:
            fh.write('%s  ./INSTALL.md\n' % ('0' * 64))
        with open(os.path.join(d2, 'INSTALL.md'), 'w', encoding='utf-8') as fh:
            fh.write('x')
        try:
            build_index(d2, '2026092300', '2026-09-01')
            check('a release with no kernel source is refused', False, '(it was accepted)')
        except SystemExit as e:
            check('a release with no kernel source is refused', 'GPLv2' in str(e))

        # A page that would link a 404 must be refused.
        d3 = os.path.join(tmp, 'missing')
        os.makedirs(d3)
        with open(os.path.join(d3, 'SHA256SUMS'), 'w', encoding='utf-8') as fh:
            fh.write('%s  ./kernel-source-stallion-2026092300.tar.gz\n' % ('0' * 64))
            fh.write('%s  ./gone.zip\n' % ('0' * 64))
        with open(os.path.join(d3, 'kernel-source-stallion-2026092300.tar.gz'), 'w') as fh:
            fh.write('x')
        try:
            build_index(d3, '2026092300', '2026-09-01')
            check('a missing file is refused', False, '(it was accepted)')
        except SystemExit as e:
            check('a missing file is refused', 'not in' in str(e))

    if failures:
        print('SELFTEST FAILED: %s' % ', '.join(failures))
        return 1
    print('SELFTEST PASS -- the page lists every signed artefact, names no other build, carries the')
    print('                kernel source, and refuses a release that would publish a dead link.')
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--dir', help='the published release directory, containing SHA256SUMS')
    ap.add_argument('--build', help='build number, e.g. 2026092300')
    ap.add_argument('--spl', help='Android security patch level, e.g. 2026-09-01')
    ap.add_argument('--out', help='where to write the page (default: stdout)')
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()
    for needed in ('dir', 'build', 'spl'):
        if not getattr(args, needed):
            ap.error('--%s is required' % needed)
    if not re.fullmatch(r'20\d{6}\d{2}', args.build):
        ap.error('--build must be YYYYMMDDNN, got %r' % args.build)

    page = build_index(args.dir, args.build, args.spl)
    if args.out:
        with open(args.out, 'w', encoding='utf-8') as fh:
            fh.write(page)
        print('wrote %s (%d bytes) for build %s' % (args.out, len(page), args.build))
    else:
        sys.stdout.write(page)
    return 0


if __name__ == '__main__':
    sys.exit(main())
