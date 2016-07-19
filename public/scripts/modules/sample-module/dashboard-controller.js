define(['angular', './sample-module'], function(angular, sampleModule) {
    'use strict';

    // Controller definition
    return sampleModule.controller('DashboardsCtrl', ['$scope','$http','$stateParams','$timeout', function($scope,$http,$stateParams,$timeout) {

       // $http.get('/api/ms/map').success(function(data){
       // 	console.log("data:");
       // 	console.log(data);
       // 	$scope.geojson=data;
       // 	});

    }]);
});
