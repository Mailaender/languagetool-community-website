/* LanguageTool Community
 * Copyright (C) 2012 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool

import org.apache.lucene.store.SimpleFSDirectory
import org.languagetool.dev.index.SearcherResult
import org.languagetool.rules.patterns.PatternRule
import org.languagetool.dev.index.Searcher
import org.apache.lucene.index.DirectoryReader

class SearchService {

    def grailsApplication

    SearcherResult checkRuleAgainstCorpus(PatternRule patternRule, Language language, int maxHits) {
        int timeoutMillis = grailsApplication.config.fastSearchTimeoutMillis
        log.info("Checking rule against ${language} corpus: ${patternRule.getElements()}, timeout: ${timeoutMillis}ms")
        String indexDirTemplate = grailsApplication.config.fastSearchIndex
        File indexDir = new File(indexDirTemplate.replace("LANG", language.getShortName()))
        if (indexDir.isDirectory()) {
            // NIOFSDirectory and MMapDirectory (as returned by FSDirectory.open()) don't play together 
            // with using Thread.interrupt(), so use SimpleFSDirectory:
            def directory = SimpleFSDirectory.open(indexDir)
            Searcher searcher = new Searcher(directory)
            searcher.setMaxHits(maxHits)
            searcher.setMaxSearchTimeMillis(timeoutMillis)
            DirectoryReader indexReader = DirectoryReader.open(directory)
            SearcherResult searcherResult = null
            try {
                searcherResult = searcher.findRuleMatchesOnIndex(patternRule, language)
            } finally {
                indexReader.close()
                directory.close()
            }
            return searcherResult
        } else {
            throw new NoDataForLanguageException(language, indexDir)
        }
    }
}
