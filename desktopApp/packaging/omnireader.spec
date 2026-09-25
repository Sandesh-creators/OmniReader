Name:           omnireader
Version:        %{_omni_version}
Release:        1%{?dist}
Summary:        EPUB reader with library, author collections and text-to-speech
License:        Proprietary
URL:            https://localhost/omnireader
Source0:        %{name}-%{version}.tar.gz

BuildArch:      x86_64
Requires:       /bin/sh
Requires:       libX11.so.6
Requires:       libXext.so.6
Requires:       libXrender.so.1
Requires:       libXtst.so.6
Requires:       libfreetype.so.6
Requires:       libfontconfig.so.1
Requires:       libGL.so.1
Requires:       zlib
Recommends:     spd-say
Recommends:     espeak-ng
Recommends:     paplay
Recommends:     alsa-utils

%description
OmniReader is a desktop EPUB reader with a scanned library, author collections,
reading progress and text-to-speech playback.

%prep
%build
%install
rm -rf %{buildroot}
mkdir -p %{buildroot}
cp -a %{_omni_stage}/. %{buildroot}/
rm -rf %{buildroot}/DEBIAN

%files
/opt/OmniReader
/usr/bin/OmniReader
/usr/share/applications/omnireader.desktop
/usr/share/icons/hicolor/256x256/apps/omnireader.png

%changelog
