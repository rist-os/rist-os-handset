#!/usr/bin/env python3
"""Classify every adb_keys entry in a target_files zip as real key material or not."""
import posixpath
import stat
import sys
import zipfile

# target_files uppercases the leading path component: /product/x -> PRODUCT/x.
def resolve(target, frm):
    t = target if target.startswith('/') else posixpath.normpath(
        posixpath.join(posixpath.dirname(frm), target))
    parts = [p for p in t.split('/') if p and p != '.']
    if not parts:
        return None
    return '/'.join([parts[0].upper()] + parts[1:])


def main():
    if len(sys.argv) != 2:
        sys.stderr.write('usage: classify_adb_keys.py <target_files.zip>\n')
        return 2
    try:
        z = zipfile.ZipFile(sys.argv[1])
        infos = z.infolist()
    except Exception as e:                                    # noqa: BLE001
        sys.stderr.write('cannot read %s: %s\n' % (sys.argv[1], e))
        return 2

    names = set(z.namelist())
    if not names:
        sys.stderr.write('%s listed ZERO entries\n' % sys.argv[1])
        return 2

    for zi in infos:
        n = zi.filename
        if not (n == 'adb_keys' or n.endswith('/adb_keys')):
            continue
        mode = (zi.external_attr >> 16) & 0o170000
        if mode == stat.S_IFLNK:
            try:
                tgt = z.read(n).decode('utf-8', 'replace').strip()
            except Exception as e:                            # noqa: BLE001
                print('%s\treal\tsymlink whose target could not be read (%s)' % (n, e))
                continue
            mapped = resolve(tgt, n)
            if mapped is None:
                print('%s\treal\tsymlink -> %r, which does not resolve' % (n, tgt))
            elif mapped in names:
                print('%s\treal\tsymlink -> %s, and %s IS in the package' % (n, tgt, mapped))
            else:
                print('%s\tdangling\tsymlink -> %s, and %s is not in the package' % (n, tgt, mapped))
        elif zi.file_size == 0:
            print('%s\tempty\tzero-length regular file' % n)
        else:
            print('%s\treal\t%d-byte regular file' % (n, zi.file_size))
    return 0


if __name__ == '__main__':
    sys.exit(main())
