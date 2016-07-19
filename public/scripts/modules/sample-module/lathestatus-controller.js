define(['angular', './sample-module'], function(angular, sampleModule) {
    'use strict';
    return sampleModule.controller('LatheStatusCtrl', ['$scope','$http','$state', function($scope,$http,$state) {

        $scope.decks='Decks';

        $scope.clock={
            now:'IN CYCLE'
        };
        
        var updateClock = function(){
            //$scope.clock.now = new Date();
            // $http.get('/api/ms/machines')
            //     .success(function(response) {
            //         $scope.lathes = response;
            //     }).error(function(){
            // });
        var jsonText='[{\"latheCode\":\"M09\",\"status\":1,\"partNumber\":\"A267180-3\",\"procedure\":\"OP110\"}]';
        // var jsonText='[{\"latheCode\":\"M09\",\"status\":1,\"partNumber\":\"A267180-3\",\"procedure\":\"OP110\"},{\"latheCode\":\"M09\",\"status\":2,\"partNumber\":\"A267180-3\",\"procedure\":\"OP110\"},{\"latheCode\":\"M09\",\"status\":3,\"partNumber\":\"A267180-3\",\"procedure\":\"OP110\"},{\"latheCode\":\"M09\",\"status\":3,\"partNumber\":\"A267180-3\",\"procedure\":\"OP110\"}]';
        // var jsonText=[{"latheCode":"M01","status":1,"partNumber":"A897186-1","procedure":"op120"},{"latheCode":"M02","status":3,"partNumber":"A897186-1","procedure":"op222"},{"latheCode":"M03","status":1,"partNumber":"A897186-1","procedure":"op120"},{"latheCode":"M04","status":1,"partNumber":"A267180-3","procedure":"OP10"},{"latheCode":"M05","status":1,"partNumber":"A267180-3","procedure":"OP110"},{"latheCode":"M06","status":2,"partNumber":"A267180-3","procedure":"OP110"},{"latheCode":"M07","status":0,"partNumber":"A267180-1","procedure":"OP110"},{"latheCode":"M08","status":1,"partNumber":"A267180-1","procedure":"OP110"},{"latheCode":"M09","status":1,"partNumber":"A267180-3","procedure":"OP110"},{"latheCode":"M10","status":1,"partNumber":"A267180-1","procedure":"OP110"},{"latheCode":"M11","status":1,"partNumber":"A267180-1","procedure":"OP110"},{"latheCode":"M12","status":0,"partNumber":"A267180-1","procedure":"OP110"},{"latheCode":"M13","status":0,"partNumber":"A267180-1","procedure":"OP110"}];
        var jobject=JSON.parse(jsonText);
        $scope.lathes =jobject;
        };
        setInterval(function(){
            $scope.$apply(updateClock);
        },5000);

        updateClock();

        

        $scope.showlathe = function($id){
            // console.log('lathe id = ' + $id);
            $state.go('voltage', {data: $id});
        };
    }]);
});