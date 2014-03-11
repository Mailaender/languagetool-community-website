'use strict';

var ruleEditor = angular.module('ruleEditor', [
  'ruleEditor.services',
  'ui.sortable',
  'ui.sortable',
  'ruleEditor.directives',
  'ngSanitize'  // show non-escaped HTML
]);

ruleEditor.controller('RuleEditorCtrl', function ($scope, $http, $q, SentenceComparator, XmlBuilder) {

  var MARKER_START = 'Marker start';
  var MARKER_END = 'Marker end';

  String.prototype.htmlEscape = function() {
    return $('<div/>').text(this.toString()).html();
  };

  String.prototype.attributeEscape = function() {
    return this.replace("\"", "&quot;").replace("'", "&apos;");
  };

  $scope.ruleName = "";
  $scope.wrongSentence = "A example sentence.";  //TODO
  $scope.correctedSentence = "An example sentence.";
  $scope.ruleMessage = "";

  $scope.patternCreated = false;
  $scope.patternEvaluated = false;
  $scope.patternElements = [];
  $scope.evaluationResult = null;  // HTML with rule matches in Wikipedia/Tatoeba
  
  $scope.patternCreationInProgress = false;
  $scope.patternEvaluationInProgress = false;

  $scope.createErrorPattern = function() {
    var self = this;
    this.patternCreationInProgress = true;
    if (this.patternCreated) {
      if (!confirm("Re-create the pattern, overwriting the existing one?")) {
        this.patternCreationInProgress = false;
        return;
      } else {
        this.patternElements = [];
      }
    }
    //TODO: don't hardcode 'en':
    var incorrectTokensPromise = SentenceComparator.incorrectTokens(__ruleEditorSentenceAnalysisUrl, "en",
      this.wrongSentence, this.correctedSentence);
    incorrectTokensPromise.then(
      function(diffTokens) {
        for (var i = 0; i < diffTokens.length; i++) {
          self.addElement(diffTokens[i]);
        }
        self.patternCreated = true;
        self.patternEvaluated = false;
        self.patternCreationInProgress = false;
      },
      function(data) {
        alert("Could not tokenize example sentences: " + data);
      },
      function(data) {}
    );
  };

  $scope.addElement = function(tokenValue) {
    this.patternElements.push({'tokenValue': tokenValue, 'tokenType': 'word'});
  };

  $scope.addMarker = function(tokenValue) {
    this.patternElements.unshift({'tokenValue': MARKER_START, 'tokenType': 'marker'});
    this.patternElements.push({'tokenValue': MARKER_END, 'tokenType': 'marker'});
  };

  $scope.hasNoMarker = function(tokenValue) {
    for (var i = 0; i < this.patternElements.length; i++) {
      if (this.patternElements[i].tokenType == 'marker') {
        return false;
      }
    }
    return true;
  };

  $scope.removeElement = function(element) {
    var index = this.patternElements.indexOf(element);
    if (this.patternElements[index].tokenType == 'marker') {
      this.removeMarkers();
    } else {
      if (index > -1) {
        this.patternElements.splice(index, 1);
      } else {
        console.warn("No element found: " + element);
      }
    }
  };

  $scope.removeMarkers = function() {
    var idx = -1;
    for (var i = 0; i < this.patternElements.length; i++) {
      if (this.patternElements[i].tokenType == 'marker') {
        this.patternElements.splice(i, 1);
      }
    }
  };
  
  /*$scope.handleReturnForToken = function() {
      // TODO: why won't this show the evaluation result div? (this.patternEvaluated = true;) 
      this.evaluateErrorPattern();
    };*/
  
  $scope.evaluateErrorPattern = function() {
    console.log("evaluateErrorPattern");
    this.patternEvaluationInProgress = true;
    // See http://stackoverflow.com/questions/19254029/angularjs-http-post-does-not-send-data:
    var data = "language=English&checkMarker=false&xml=" + this.buildXml();   // TODO: not always English
    var ctrl = this;
    var url = __ruleEditorEvaluationUrl;  // GSP doesn't evaluate in JS, so we need this hack
    $http({
      url: url,
      method: 'POST',
      data: data,
      // See http://stackoverflow.com/questions/19254029/angularjs-http-post-does-not-send-data:
      headers: {'Content-Type': 'application/x-www-form-urlencoded'}
    }).success(function(data) {
        // TODO: slooooow! see https://github.com/Pasvaz/bindonce
        //ctrl.evaluationResult = data;
        ctrl.patternEvaluated = true;
        $('#evaluationResult').html(data);
        ctrl.patternEvaluated = true;
        ctrl.patternEvaluationInProgress = false;
      })
      .error(function(data, status, headers, config) {
        // TODO: see above:
        $('#evaluationResult').html(data);
        ctrl.patternEvaluationInProgress = false;
      });
    this.patternEvaluated = true;
  };

  $scope.buildXmlForElement = function(elem) {
    var xml = "";
    if (elem.tokenType == 'word') {
      xml += "  <token>" + elem.tokenValue + "</token>\n";
    } else if (elem.tokenType == 'posTag') {
      xml += "  <token postag='" + elem.tokenValue.htmlEscape() + "'/>\n";
    } else if (elem.tokenType == 'regex') {
      xml += "  <token regexp='yes'>" + elem.tokenValue + "</token>\n";
    } else if (elem.tokenType == 'marker' && elem.tokenValue == MARKER_START) {
      xml += "  <marker>\n";
    } else if (elem.tokenType == 'marker' && elem.tokenValue == MARKER_END) {
      xml += "  </marker>\n";
    } else {
      console.warn("Unknown token type '" + elem.tokenType + "'");
    }
    return xml;
  };

  $scope.buildXml = function() {
    return XmlBuilder.buildXml(this);
  };

  $scope.showXml = function() {
    alert(this.buildXml());
  };

});