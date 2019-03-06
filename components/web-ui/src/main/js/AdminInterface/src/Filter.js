import React, { Component } from 'react';
import './Filter.css';

class Filter extends Component {
  constructor(props) {
    super(props);
this.state={active:true,
            inactive:true,
            disable:true }

                     }

activechange = (e) =>{this.setState({active:!this.state.active})}                     
inactivechange = (e) =>{this.setState({inactive:!this.state.inactive})} 
disablechange = (e) =>{this.setState({disable:!this.state.disable})} 

submit = (e)=>{e.preventDefault();
  this.props.submit(this.state);
               }

  render() {

    return (
      <div className='filter'>
      <fieldset>
      <legend className='legend'>Filters</legend>
      

        <form onSubmit={this.submit}>
         <input type="checkbox" name="active" value={this.state.active} onChange={this.activechange} defaultChecked={this.state.active}/><span>Active</span>
         <input type="checkbox" name="inactive" value={this.state.inactive} onChange={this.inactivechange}defaultChecked={this.state.inactive}/><span>Inactive</span>
         <input type="checkbox" name="disable" value={this.state.disable} onChange={this.disablechange}defaultChecked={this.state.disable}/><span>Disable</span>
         <button>Submit</button>
        </form>
        </fieldset>
      </div>
    )
  }
}

export default Filter;
