define(['angular', './sample-module'], function(angular, sampleModule) {
		    'use strict';
		    return sampleModule.controller('HistoryFileCtrl', ['$scope','$http', function($scope,$http,$stateParams) {
		    	$scope.word='test';
		    }]);
		});