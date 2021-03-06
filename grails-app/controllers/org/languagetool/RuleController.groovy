/* LanguageTool Community 
 * Copyright (C) 2008 Daniel Naber (http://www.danielnaber.de)
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

import org.languagetool.rules.*
import org.languagetool.rules.patterns.*

/**
 * Display and edit error rules.
 */
class RuleController extends BaseController {

    def beforeInterceptor = [action: this.&auth,
            only: ['copyAndEditRule', 'edit', 'doEdit',
                    'createRule', 'change']]

    def index = { redirect(action:list,params:params) }

    def list = {
        int max = 10
        int offset = 0
        if (!params.lang) params.lang = "en"
        if (params.offset) offset = Integer.parseInt(params.offset)
        if (params.max) max = Integer.parseInt(params.max)
        String langCode = getLanguage()
        Language langObj = Language.getLanguageForShortName(langCode)
        JLanguageTool lt = new JLanguageTool(langObj)
        lt.activateDefaultPatternRules()
        List rules = lt.getAllRules()
        Map patternRuleIdToUserRuleId = new HashMap()
        if (session.user) {
            // find the user's personal rules:
            List userRules = UserRule.findAllByUserAndLang(session.user, langCode)
            for (userRule in userRules) {
                // make temporary pattern rules:
                PatternRule patternRule = userRule.toPatternRule(true)
                //patternRule.dynamicId = userRule.id
                // TODO: ugly hack, find a better solution to transfer the id of the dynamic rule:
                //patternRule.patternRule.message = patternRule.message + "__//__" + userRule.id
                patternRuleIdToUserRuleId.put(patternRule.id, userRule.id)
                rules.add(patternRule)
            }
        }
        if (params.filter) {
            rules = filterRules(rules, params.filter)
        }
        if (params.sort) {
            def sortF = SortField.pattern
            if (params.sort == 'description') sortF = SortField.description
            if (params.sort == 'category') sortF = SortField.category
            Collections.sort(rules, new RuleComparator(sortF,
                    params.order == 'desc' ? SortDirection.desc : SortDirection.asc));
        }
        int ruleCount = rules.size()
        if (ruleCount == 0) {
            rules = []
        } else {
            rules = rules[offset..Math.min(rules.size()-1, offset+max-1)]
        }
        Set disabledRuleIDs = new HashSet()      // empty = all rules activated
        if (session.user) {
            LanguageConfiguration langConfig = getLangConfigForUser(langObj.shortName, session)
            if (langConfig) {
                Set disabledRules = langConfig.getDisabledRules()
                for (rule in disabledRules) {
                    disabledRuleIDs.add(rule.ruleID)
                }
            }
        }
        [ ruleList: rules, ruleCount: ruleCount, languages: Language.REAL_LANGUAGES,
                disabledRuleIDs: disabledRuleIDs, patternRuleIdToUserRuleId: patternRuleIdToUserRuleId,
                language: langObj]
    }

    private filterRules(List rules, String filter) {
        filter = filter.toLowerCase()
        List filtered = []
        for (rule in rules) {
            // match pattern:
            if (rule instanceof PatternRule) {
                PatternRule pRule = (PatternRule)rule
                if (pRule.toPatternString().toLowerCase().contains(filter)) {
                    filtered.add(rule)
                    continue
                }
            }
            // match description or id:
            if (rule.description.toLowerCase().contains(filter) || rule.id.toLowerCase() == filter) {
                filtered.add(rule)
                continue
            }
            // match category (TODO: doesn't work properly for user rules):
            String catName = rule.category.name.toLowerCase()
            if (catName.contains(filter)) {
                filtered.add(rule)
            }
        }
        return filtered
    }

    /**
     * Check a given text with a single rule.
     */
    def checkTextWithRule = {
        // get all information needed to display "show" page:
        String langCode = "en"
        if (params.lang) langCode = params.lang
        JLanguageTool lt = new JLanguageTool(Language.getLanguageForShortName(langCode))
        lt.activateDefaultPatternRules()
        Rule selectedRule
        boolean isUserRule = false
        try {
            // user rules have an internal (integer) id:
            int ruleId = Integer.parseInt(params.id)
            UserRule userRule = UserRule.get(ruleId)
            selectedRule = userRule.toPatternRule(true)
            isUserRule = true
        } catch (NumberFormatException ignored) {
            selectedRule = getSystemRuleById(params.id, params.subId, lt)
        }
        if (!selectedRule) {
            flash.message = "No rule with id ${params.id.encodeAsHTML()}"
            redirect(action:list)
        }
        int disableId = getEnableDisableId(langCode)
        // disable all rules except one:
        List rules = lt.getAllRules()
        for (Rule rule in rules) {
            if (rule.id == params.id) {
                if (rule.isDefaultOff()) {
                    lt.enableDefaultOffRule(rule.id)
                } else {
                    lt.enableRule(rule.id)
                }
            } else {
                lt.disableRule(rule.id)
            }
        }
        if (isUserRule) {
            lt.addRule(selectedRule)
        }
        // now actually check the text:
        String text = params.text
        int maxTextLen = grailsApplication.config.max.text.length
        if (text.size() > maxTextLen) {
            text = text.substring(0, maxTextLen)
            flash.message = "The text is too long, only the first $maxTextLen characters have been checked"
        }
        int corpusMatchCount = countCorpusMatches(langCode, selectedRule.id)
        List ruleMatches = lt.check(text)
        render(view:'show', model: [ hideRuleLink: true, rule: selectedRule, isDisabled: disableId != -1, disableId: disableId,
                textToCheck: params.text, matches: ruleMatches, ruleId: params.id,
                isUserRule: isUserRule, corpusMatchCount: corpusMatchCount],
                contentType: "text/html", encoding: "utf-8")
    }

    def createRule = {
        String langCode = getLanguage()
        //FIXME: generate a better unique ID!
        Rule newRule = new PatternRule("-1",
                Language.getLanguageForShortName(langCode),
                [], "", "", "")
        render(view:'edit', model:[ rule: newRule, lang: langCode, isUserRule: true ])
    }

    def edit = {
        String langCode = getLanguage()
        SelectedRule rule = getRuleById(params.id, params.subId, langCode)
        Rule selectedRule = rule.rule
        render(view:'edit', model: [ rule: selectedRule, lang: langCode,
                isUserRule: rule.isUserRule, ruleId: params.id ],
                contentType: "text/html", encoding: "utf-8")
    }

    def doEdit = {
        String langCode = getLanguage()
        UserRule userRule
        if (params.id == "null") {
            // user just wants to create a new rule:
            userRule = new UserRule()
        } else {
            // user wants to edit existing rule:
            userRule = UserRule.get(params.id)
        }
        // get all pattern elements:
        int i = 0
        List elements = []
        // TODO: move case setting to token level??
        boolean isCaseSensitive = false
        if (params['case_sensitive']) {
            isCaseSensitive = true
        }
        while (i < grailsApplication.config.maxPatternElements) {
            String pattern = params['pattern_'+i]
            if (pattern.trim() != "") {
                Element el = new Element(params['pattern_'+i], isCaseSensitive, false, false)
                elements.add(el)
            }
            i++
        }
        PatternRule patternRule = new PatternRule(params.id,
                Language.getLanguageForShortName(langCode),
                elements, params.description, params.message, "fakeShortMessage")
        userRule.pattern = patternRule.toXML()
        userRule.description = patternRule.description
        userRule.message = patternRule.message
        userRule.user = session.user
        userRule.lang = langCode
        //log.info("#######"+patternRule.toXML()+"'")

        boolean saved = userRule.save()
        if (!saved) {
            throw new Exception("Cannot save rule: ${userRule.errors}")
        }
        flash.message = "Changes saved"
        redirect(action:'show', id:userRule.id, params:[lang:params.lang])
    }

    def copyAndEditRule = {
        String langCode = getLanguage()
        JLanguageTool lt = new JLanguageTool(Language.getLanguageForShortName(langCode))
        lt.activateDefaultPatternRules()
        Rule origRule = getSystemRuleById(params.id, params.subId, lt)
        if (!origRule) {
            throw new Exception("No rule found for id ${params.id}, language $langCode")
        }
        if (!(origRule instanceof PatternRule)) {
            throw new Exception("Cannot copy ${params.id}, only PatternRules can be copied")
        }

        PatternRule origPatternRule = (PatternRule)origRule
        //log.info("copyAndEditRule msg: ${origPatternRule.getDescription()} (umlaut test: öäüÖÄÜß)")
        UserRule userRule = new UserRule(originalRuleId: params.id, lang:params.lang,
                description: origPatternRule.getDescription(),
                message: origPatternRule.getMessage(),
                pattern: "<rules lang=\"$langCode\">" + origPatternRule.toXML() + "</rules>",
                user: session.user)
        boolean saved = userRule.save()
        if (!saved) {
            throw new Exception("Could not save copy of rule ${params.id.encodeAsHTML()}: ${userRule.errors}")
        }
        redirect(action:'show', id:userRule.id, params:[lang:params.lang])
    }

    def show = {
        String langCode = getLanguage()
        SelectedRule rule = getRuleById(params.id, params.subId, langCode)
        Rule selectedRule = rule.rule
        boolean isUserRule = rule.isUserRule
        int disableId = getEnableDisableId(langCode)
        if (!selectedRule) {
            log.warn("No rule with id ${params.id}, subId ${params.subId} and language ${langCode}")
            flash.message = "No rule with id ${params.id.encodeAsHTML()}, subId ${params.subId.encodeAsHTML()}"
            redirect(action:list)
            return
        }
        String textToCheck = ""
        if (params.textToCheck) {
            textToCheck = params.textToCheck
        }
        String ruleSubId = null
        if (selectedRule instanceof PatternRule) {
            ruleSubId = ((PatternRule)selectedRule).getSubId()
        }
        int corpusMatchCount = countCorpusMatches(langCode, selectedRule.id)
        render(view:'show', model: [rule: selectedRule, ruleSubId: ruleSubId, isDisabled: disableId != -1, disableId: disableId,
                isUserRule: isUserRule, ruleId: params.id, textToCheck: textToCheck, corpusMatchCount: corpusMatchCount],
                contentType: "text/html", encoding: "utf-8")
    }

    def showRuleXml = {
        String langCode = getLanguage()
        Language language = Language.getLanguageForShortName(langCode)
        PatternRuleId id = params.subId ? new PatternRuleId(params.id, params.subId) : new PatternRuleId(params.id)
        PatternRuleXmlCreator ruleXmlCreator = new PatternRuleXmlCreator()
        String ruleAsXml = ruleXmlCreator.toXML(id, language)
        render(template: 'xml', model: [ruleAsXml: ruleAsXml, language: language])
    }

    private int countCorpusMatches(String langCode, String ruleId) {
        def matchCriteria = CorpusMatch.createCriteria()
        def corpusMatchCount = matchCriteria.count {
            eq('ruleID', ruleId)
            eq('languageCode', langCode)
            eq('isVisible', true)
        }
        return corpusMatchCount
    }

    private String getLanguage() {
        String lang = "en"
        if (params.lang) {
            lang = params.lang
        }
        assert(lang)
        return lang
    }

    private SelectedRule getRuleById(String id, String subId, String lang) {
        Rule selectedRule
        boolean isUserRule
        try {
            int userRuleId = Integer.parseInt(id)
            log.info("getting user rule with id $userRuleId")
            UserRule selectedUserRule = UserRule.get(userRuleId)
            // build a temporary rule:
            selectedRule = selectedUserRule.toPatternRule(true)
            isUserRule = true
        } catch (NumberFormatException ignored) {
            JLanguageTool lt = new JLanguageTool(Language.getLanguageForShortName(lang))
            lt.activateDefaultPatternRules()
            selectedRule = getSystemRuleById(id, subId, lt)
            isUserRule = false
        }
        return new SelectedRule(isUserRule: isUserRule, rule: selectedRule)
    }

    private Rule getSystemRuleById(String id, String subId, JLanguageTool lt) {
        log.debug("Getting system rule with id $id")
        Rule selectedRule = null
        List rules = lt.getAllRules()
        for (Rule rule in rules) {
            boolean subIdMatchIfNeeded = true
            if (rule instanceof PatternRule && subId != null) {
                PatternRule pRule = (PatternRule) rule
                subIdMatchIfNeeded = pRule.subId == subId
            }
            if (rule.id == params.id && subIdMatchIfNeeded) {
                selectedRule = rule
                break
            }
        }
        return selectedRule
    }

    private int getEnableDisableId(String lang) {
        LanguageConfiguration langConfig = getLangConfigForUser(lang, session)
        int enableDisableID = -1
        if (langConfig) {
            Set disabledRules = langConfig.getDisabledRules()
            for (disabledRule in disabledRules) {
                if (disabledRule.ruleID == params.id) {
                    enableDisableID = disabledRule.id
                    break
                }
            }
        }
        return enableDisableID
    }

    def change = {
        if (!session.user) {
            throw new Exception("Not logged in")
        }
        String lang = "en"
        if (params.lang) lang = params.lang
        LanguageConfiguration langConfig = getLangConfigForUser(lang, session)
        if (!langConfig) {
            log.info("Creating language configuration for ${session.user}, language $lang")
            langConfig = new LanguageConfiguration(language:lang)
            session.user.addToLanguagesConfigurations(langConfig)
            def saved = session.user.save()
            if (!saved) {
                throw new Exception("Could not save LanguageConfiguration: ${langConfig.errors}")
            }
        }
        Set<DisabledRule> disabledRules = langConfig.getDisabledRules()
        Set<String> disabledRuleIDs = []
        for (disabledRule in disabledRules) {
            disabledRuleIDs.add(disabledRule.ruleID)
        }
        String message
        if (!params.active) {
            // de-activate rule
            langConfig.addToDisabledRules(new DisabledRule(ruleID:params.id))
            message = "Rule has been deactivated"
            log.info("Rule ${params.id} has been deactivated by ${session.user.username}")
        } else {
            // activate rule
            for (disabledRule in disabledRules) {
                if (disabledRule.id == Integer.parseInt(params.disableId)) {
                    langConfig.removeFromDisabledRules(disabledRule)
                    message = "Rule has been activated"
                    log.info("Rule ${disabledRule.id} has been activated by ${session.user.username}")
                    break
                }
            }
        }
        def saved = session.user.save()
        if (!saved) {
            throw new Exception("Could not save user: ${session.user.errors}")
        }
        flash.message = message
        redirect(action:show, params: [id: params.id, lang: params.lang])
    }

    private static LanguageConfiguration getLangConfigForUser(String lang, def session) {
        if (session.user) {
            def user = session.user
            user.refresh()
            Set<LanguageConfiguration> langConfigs = user.languagesConfigurations
            if (langConfigs) {
                for (langConfig in langConfigs) {
                    if (langConfig.language == lang) {
                        return langConfig
                    }
                }
            }
        }
        return null
    }

}

class SelectedRule {
    Rule rule
    boolean isUserRule
}
 