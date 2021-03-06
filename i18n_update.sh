#!/bin/sh
# Download latest community website translations from Transifex and copy them over the existing local files.

# Transifex username and password
USERNAME=dnaber
PASSWORD=fixme

rm -I i18n-temp
mkdir i18n-temp
cd i18n-temp

# list of languages in the same order as on https://www.transifex.com/projects/p/languagetool/:
for lang in en ast be br ca zh da nl eo fr gl de el_GR it pl ru sl es tl uk ro sk cs sv is lt km pt_PT pt_BR
do
  SOURCE=downloaded.tmp
  # download and hackish JSON cleanup:
  curl --user $USERNAME:$PASSWORD http://www.transifex.net/api/2/project/languagetool/resource/community-website/translation/$lang/?file >$SOURCE
  TARGET="../grails-app/i18n/messages_${lang}.properties"
  echo "Moving $SOURCE to $TARGET"
  mv $SOURCE $TARGET
done

# messages.properties is used as the fallback, so automatically build it from English to avoid redundancy: 
TARGET=../grails-app/i18n/messages.properties
echo "# --- DO NOT MODIFY --- auto-generated by i18n_update.sh" >$TARGET
cat ../grails-app/i18n/messages_common.properties >>$TARGET
echo "" >>$TARGET
cat ../grails-app/i18n/messages_en.properties >>$TARGET

# special case: if this is named pt_PT it never becomes active because we use "lang=xx" links
# in the web app that don't contain the country code:
echo "Special case: copying 'pt_PT' to 'pt'"
mv ../grails-app/i18n/messages_pt_PT.properties ../grails-app/i18n/messages_pt.properties

cd ..
rm -r i18n-temp
